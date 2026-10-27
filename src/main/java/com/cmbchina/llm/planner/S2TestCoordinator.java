package com.cmbchina.llm.planner;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.FileOperationResult;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.verify.TestSourceInspector;
import com.cmbchina.llm.workspace.WorkspaceService;
import com.github.javaparser.ast.CompilationUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从当前任务 S1 写入建立 S2 清单，逐类推进测试生成和修复范围保护。
 * 状态仅保存在任务上下文，不扫描工程、不共享任务间的可变进度。
 *
 * @author summer
 * @date 2026-10-20 00:00:00
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class S2TestCoordinator {

    private static final String STATE = "s2_test_state";
    private final WorkspaceService workspaceService;
    private final MavenDependencySupport dependencySupport;
    private final TestSourceInspector inspector;
    private final TemplateContractValidator contractValidator;

    public void initialize(AgentContext context) {
        if (context.getVariables().containsKey(STATE)) {
            return;
        }
        State state = new State();
        File pom = dependencySupport.modulePom(context);
        if (pom == null) {
            throw new IllegalArgumentException("S2 找不到入口模块 POM");
        }
        state.pom = workspaceService.relativePath(pom);
        File module = pom.getParentFile();
        File sourceRoot = workspaceService.resolve(new File(module, "src/main/java").getPath());
        Set<String> paths = new LinkedHashSet<>();
        for (Observation observation : context.getObservations()) {
            if (!observation.isSuccess() || !isMutation(observation.getTool())) {
                continue;
            }
            String path = path(observation);
            if (path == null) {
                continue;
            }
            File file = workspaceService.resolve(path);
            if (file.toPath().startsWith(sourceRoot.toPath()) && file.getName().endsWith(".java")) {
                paths.add(workspaceService.relativePath(file));
            }
        }
        for (String path : paths) {
            if (state.entries.size() >= 200) {
                throw new IllegalArgumentException("S2 本次源码条目超过 200，请拆分需求");
            }
            File file = workspaceService.resolve(path);
            CompilationUnit unit = inspector.parse(inspector.read(file));
            String reason = inspector.skipReason(unit);
            if (reason != null) {
                state.skipped.put(path, reason);
                log.info("S2 skip source={} reason={}", path, reason);
                continue;
            }
            String sourceClass = file.getName().substring(0, file.getName().length() - 5);
            String pkg = unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
            String relative = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + sourceClass + "Test.java";
            Entry entry = new Entry();
            entry.source = path;
            entry.sourceClass = sourceClass;
            entry.test = workspaceService.relativePath(workspaceService.resolve(
                    new File(module, "src/test/java/" + relative).getPath()));
            entry.testClass = (pkg.isEmpty() ? "" : pkg + ".") + sourceClass + "Test";
            state.entries.add(entry);
        }
        if (state.entries.isEmpty()) {
            throw new IllegalArgumentException("S2 没有可执行测试条目；跳过原因=" + state.skipped);
        }
        context.getVariables().put(STATE, state);
        log.info("S2 initialized module={} entries={} skipped={}", state.pom, state.entries.size(), state.skipped);
    }

    public CodePlan ensureCurrentRead(AgentContext context) {
        List<PlanStep> steps = new ArrayList<>();
        for (String path : currentPaths(context)) {
            File file = workspaceService.resolve(path);
            if (file.isFile() && freshRead(context, path) == null) {
                steps.add(PlanStep.builder().order(steps.size() + 1).tool("read_file")
                        .input(ToolInput.builder().path(path).startLine(1).endLine(10000).build())
                        .description("S2 读取当前真实源码、已有测试和模块 POM").build());
            }
        }
        return steps.isEmpty() ? null : CodePlan.builder().task(context.getTask()).steps(steps).build();
    }

    public boolean advance(AgentContext context) {
        State state = state(context);
        Entry entry = current(state);
        if (entry == null) {
            return true;
        }
        List<String> errors = validate(entry);
        if (freshRead(context, entry.source) == null
                || workspaceService.resolve(entry.test).isFile() && freshRead(context, entry.test) == null) {
            return false;
        }
        if (!errors.isEmpty()) {
            return false;
        }
        entry.complete = true;
        log.info("S2 item completed source={} test={} progress={}/{}", entry.source, entry.test,
                state.entries.stream().filter(value -> value.complete).count(), state.entries.size());
        return current(state) == null;
    }

    public List<String> validateAll(AgentContext context) {
        State state = state(context);
        List<String> errors = new ArrayList<>();
        for (Entry entry : state.entries) {
            errors.addAll(validate(entry));
        }
        return errors;
    }

    private List<String> validate(Entry entry) {
        try {
            List<String> errors = inspector.validate(inspector.read(workspaceService.resolve(entry.test)),
                    entry.sourceClass, entry.testClass);
            return errors.isEmpty() ? errors : Collections.singletonList(entry.test + ": " + errors);
        } catch (RuntimeException exception) {
            return Collections.singletonList(entry.test + ": " + exception.getMessage());
        }
    }

    public String testClassNames(AgentContext context) {
        List<String> names = new ArrayList<>();
        for (Entry entry : state(context).entries) {
            names.add(entry.testClass);
        }
        return String.join(",", names);
    }

    public String modulePom(AgentContext context) {
        return state(context).pom;
    }

    public int completedCount(AgentContext context) {
        return (int) state(context).entries.stream().filter(entry -> entry.complete).count();
    }

    public String buildRules(AgentContext context) {
        State state = state(context);
        Entry entry = current(state);
        return "当前 CODING S2：按清单逐类生成/补齐测试，全部条目通过系统门禁后才能统一执行。\n"
                + "当前业务源码: " + (entry == null ? "全部完成" : entry.source)
                + "；唯一测试产物: " + (entry == null ? "全部完成" : entry.test)
                + "；模块 POM: " + state.pom + "\n"
                + "当前测试文件存在: " + (entry != null && workspaceService.resolve(entry.test).isFile()) + "\n"
                + "当前未满足项: " + (entry == null ? "无" : validate(entry)) + "\n"
                + "使用真实业务签名和已有 JUnit4/5、断言/Mockito；测试须有实际业务断言。"
                + "覆盖正常、异常、空值及本需求分支；HTTP/DB/私有服务使用 mock，不启动 Spring 全上下文或真实网络。"
                + "已有测试先读后局部 patch；禁止重写业务、空壳、恒定值断言、禁用、assume 或删除既有测试/断言。"
                + "缺测试依赖只局部补模块 POM test scope，版本复用已有管理；不能猜私有依赖坐标或版本。"
                + "新增依赖使用已读 POM 中真实且唯一的 dependencies 结束标签作局部插入锚点；"
                + "不要为添加依赖重排已有 dependency/artifactId 的格式，不能拼造 oldText。"
                + "Maven 由服务端验证阶段执行，模型不得调用 run_test、shell、exec 或其他命令工具。\n";
    }

    public List<Observation> observations(AgentContext context) {
        List<Observation> result = new ArrayList<>();
        for (String path : currentPaths(context)) {
            Observation observation = freshRead(context, path);
            if (observation != null) {
                result.add(observation);
            }
        }
        return result;
    }

    public void validateStep(AgentContext context, PlanStep step) {
        if (!(step.getInput() instanceof ToolInput) || !isMutation(step.getTool())) {
            return;
        }
        State state = state(context);
        ToolInput input = (ToolInput) step.getInput();
        String path = workspaceService.relativePath(workspaceService.resolve(input.getPath()));
        boolean repair = context.getPhase() == AgentPhase.REPAIR;
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(state.pom);
        for (Entry entry : state.entries) {
            if (repair || entry == current(state)) {
                allowed.add(entry.test);
            }
            if (repair) {
                allowed.add(entry.source);
            }
        }
        if (!allowed.contains(path)) {
            if (Arrays.asList("BeeTraceClientHelper.java", "HttpClientInjectAdapter.java")
                    .contains(new File(path).getName())) {
                throw new IllegalArgumentException("禁止创建或改写公共追踪组件的业务包替身: " + path
                        + "；应在清单内的 HttpClientUtil 补齐真实公共包 import 与 beeTraceClientHelper Bean 字段。"
                        + "固定类型为 com.cmb.bee.tracer.util.BeeTraceClientHelper 和 "
                        + "com.cmb.bee.tracer.codec.HttpClientInjectAdapter；不得发明 getInstance 或自行实现公共类。"
                        + "真实类型不可用时核实依赖，缺少坐标或版本依据则报告缺失信息。");
            }
            throw new IllegalArgumentException("S2 只允许修改当前测试或修复清单内文件: " + path);
        }
        if (workspaceService.resolve(path).isFile() && freshRead(context, path) == null) {
            throw new IllegalArgumentException("S2 修改已有文件前必须重新读取最新完整内容: " + path);
        }
    }

    /** 在整份计划预演后的真实内容上检查，支持同文件多段局部补丁。 */
    public void validatePreview(AgentContext context, File file, String before, String after) {
        State state = state(context);
        String path = workspaceService.relativePath(file);
        for (Entry entry : state.entries) {
            if (entry.source.equals(path) && "HttpClientUtil".equals(entry.sourceClass)
                    && context.getCompletedTemplates().stream()
                    .anyMatch(template -> template.contains("03-http-client-util"))) {
                List<String> errors = contractValidator.validateHttpClientSource(after);
                if (!errors.isEmpty()) {
                    throw new IllegalArgumentException("S2 修复不得破坏 S1 公共追踪契约: " + errors);
                }
            }
            if (entry.test.equals(path)) {
                Map<String, Integer> previous = before.isEmpty() ? Collections.emptyMap() : inspector.assertions(before);
                Map<String, Integer> next = inspector.assertions(after);
                for (Map.Entry<String, Integer> method : previous.entrySet()) {
                    if (!next.containsKey(method.getKey()) || next.get(method.getKey()) < method.getValue()) {
                        throw new IllegalArgumentException("禁止删除既有测试方法或减少断言: " + path + " " + method.getKey());
                    }
                }
                if (!before.isEmpty()) {
                    inspector.preserveAssertions(before, after);
                }
                List<String> errors = validateSource(entry, after);
                if (!errors.isEmpty()) {
                    throw new IllegalArgumentException("测试修改不满足门禁: " + errors);
                }
            }
        }
        if (state.pom.equals(path)) {
            String normalized = after.toLowerCase(java.util.Locale.ROOT);
            if (normalized.matches("(?s).*<(?:skiptests|maven.test.skip|skip|testfailureignore)>\\s*true\\s*</.*")
                    && !before.equals(after)) {
                throw new IllegalArgumentException("禁止通过 POM 跳过测试或忽略失败");
            }
            Set<String> missingVersions = new LinkedHashSet<>();
            if (context.getPhase() == AgentPhase.REPAIR) {
                Matcher missing = Pattern.compile("'dependencies\\.dependency\\.version'\\s+for\\s+"
                        + "([\\w.-]+:[\\w.-]+):[^\\s]+\\s+is missing")
                        .matcher(String.join("\n", context.getVerifyErrors()));
                while (missing.find()) {
                    missingVersions.add(missing.group(1));
                }
            }
            dependencySupport.validateTestPom(before, after, context.getPhase() != AgentPhase.REPAIR, missingVersions);
        }
    }

    private List<String> validateSource(Entry entry, String source) {
        return inspector.validate(source, entry.sourceClass, entry.testClass);
    }

    public String fingerprint(AgentContext context) {
        State state = state(context);
        Set<String> paths = new LinkedHashSet<>();
        paths.add(state.pom);
        for (Entry entry : state.entries) {
            paths.add(entry.source);
            paths.add(entry.test);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String path : paths) {
                File file = workspaceService.resolve(path);
                digest.update(path.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update((file.isFile() ? inspector.read(file) : "<missing>").getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.Base64.getEncoder().encodeToString(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<String> currentPaths(AgentContext context) {
        State state = state(context);
        List<String> paths = new ArrayList<>();
        Entry entry = current(state);
        if (entry != null) {
            paths.add(entry.source);
            paths.add(entry.test);
        }
        for (File pom : dependencySupport.pomFiles(context)) {
            paths.add(workspaceService.relativePath(pom));
        }
        return paths;
    }

    private Observation freshRead(AgentContext context, String path) {
        File target = workspaceService.resolve(path);
        List<Observation> history = context.getObservations();
        for (int index = history.size() - 1; index >= 0; index--) {
            Observation observation = history.get(index);
            if (!observation.isSuccess()) {
                continue;
            }
            String observedPath = path(observation);
            if (observedPath == null) {
                continue;
            }
            try {
                if (!target.equals(workspaceService.resolve(observedPath))) {
                    continue;
                }
            } catch (IllegalArgumentException outsideWorkspace) {
                continue;
            }
            if (isMutation(observation.getTool())) {
                return null;
            }
            if ("read_file".equals(observation.getTool()) && observation.getResult() instanceof FileContent) {
                FileContent read = (FileContent) observation.getResult();
                String snapshot = String.join("\n", read.getLines()).replaceAll("(?m)^\\d+: ?", "");
                if (withoutTrailingWhitespace(snapshot).equals(
                        withoutTrailingWhitespace(inspector.read(target).replace("\r\n", "\n")))) {
                    return observation;
                }
            }
        }
        return null;
    }

    private String withoutTrailingWhitespace(String source) {
        int end = source.length();
        while (end > 0) {
            int character = source.codePointBefore(end);
            if (!Character.isWhitespace(character)) {
                break;
            }
            end -= Character.charCount(character);
        }
        return source.substring(0, end);
    }

    private String path(Observation observation) {
        if (observation.getResult() instanceof FileOperationResult) {
            return ((FileOperationResult) observation.getResult()).getPath();
        }
        if (observation.getResult() instanceof FileContent) {
            return ((FileContent) observation.getResult()).getPath();
        }
        if (observation.getInput() instanceof ToolInput) {
            return ((ToolInput) observation.getInput()).getPath();
        }
        if (observation.getInput() instanceof Map) {
            Object value = ((Map<?, ?>) observation.getInput()).get("path");
            return value instanceof String ? (String) value : null;
        }
        return null;
    }

    private boolean isMutation(String tool) {
        return Arrays.asList("write_file", "create_file", "patch_file", "edit_file").contains(tool);
    }

    private State state(AgentContext context) {
        initialize(context);
        return (State) context.getVariables().get(STATE);
    }

    private Entry current(State state) {
        return state.entries.stream().filter(entry -> !entry.complete).findFirst().orElse(null);
    }

    private static class State {
        private String pom;
        private final List<Entry> entries = new ArrayList<>();
        private final Map<String, String> skipped = new LinkedHashMap<>();
    }

    private static class Entry {
        private String source;
        private String sourceClass;
        private String test;
        private String testClass;
        private boolean complete;
    }
}
