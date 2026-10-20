package com.cmbchina.llm.planner;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.skill.Skill;
import com.cmbchina.llm.skill.SkillMatcher;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.workspace.WorkspaceService;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 参考模板的路径识别、读取前置和观察记录协调器。
 *
 * @author summer
 * @date 2026-10-20 00:00:00
 */
@Component
@Slf4j
public class SkillTemplateCoordinator {

    private final SkillMatcher skillMatcher;
    private final TemplateContractValidator contractValidator;
    private final WorkspaceService workspaceService;
    private final MavenDependencySupport dependencySupport;

    @Value("${codex.agent.test-generation-enabled:false}")
    private boolean testGenerationEnabled;

    public SkillTemplateCoordinator(SkillMatcher skillMatcher, TemplateContractValidator contractValidator,
                                    WorkspaceService workspaceService, MavenDependencySupport dependencySupport) {
        this.skillMatcher = skillMatcher;
        this.contractValidator = contractValidator;
        this.workspaceService = workspaceService;
        this.dependencySupport = dependencySupport;
    }

    public CodePlan ensureTemplatesRead(AgentContext context, CodePlan plan) {
        if (context.getPhase() != AgentPhase.CODING) {
            return plan;
        }
        String selected = currentTemplate(context);
        if (selected == null || hasReadTemplate(selected, context.getObservations())) {
            return plan;
        }
        log.info("S1 read current template={} completed={}", selected, context.getCompletedTemplates());
        return CodePlan.builder()
                .task(context.getTask())
                .steps(singleTemplateRead(selected))
                .build();
    }

    public boolean hasUnreadTemplate(AgentContext context, List<Observation> observations) {
        String current = currentTemplate(context);
        return current != null && !hasReadTemplate(current, observations);
    }

    public CodePlan ensureCurrentArtifactsRead(AgentContext context) {
        if (context.getPhase() != AgentPhase.CODING) {
            return null;
        }
        List<PlanStep> steps = new ArrayList<>();
        for (String path : currentArtifactPaths(context)) {
            if (workspaceService.resolve(path).isFile() && latestArtifactRead(path, context) == null) {
                steps.add(PlanStep.builder().order(steps.size() + 1).tool("read_file")
                        .input(ToolInput.builder().path(path).startLine(1).endLine(1000).build())
                        .description("读取当前模板已有产物，保留原内容并使用 patch_file 局部修改").build());
            }
        }
        if (steps.isEmpty()) {
            return null;
        }
        log.info("S1 read existing artifacts template={} paths={}", currentTemplate(context), currentArtifactPaths(context));
        return CodePlan.builder().task(context.getTask()).steps(steps).build();
    }

    public List<Observation> currentArtifactObservations(AgentContext context) {
        List<Observation> result = new ArrayList<>();
        for (String path : currentArtifactPaths(context)) {
            Observation observation = latestArtifactRead(path, context);
            if (observation != null) {
                result.add(observation);
            }
        }
        // 入口模板需要复用已完成的调用类和配置类；不能被最近三条普通观察挤出提示词。
        String current = currentTemplate(context);
        if (current != null && current.contains("05-entry-and-mapping")) {
            for (String name : Arrays.asList("AhiHttpInvokerHandler.java", "ReadConfig.java")) {
                List<Observation> history = context.getObservations();
                for (int index = history.size() - 1; index >= 0; index--) {
                    Observation observation = history.get(index);
                    if (!observation.isSuccess() || !(observation.getResult() instanceof FileContent)) {
                        continue;
                    }
                    String path = ((FileContent) observation.getResult()).getPath();
                    if (path != null && name.equals(new File(path).getName())) {
                        Observation latest = latestArtifactRead(path, context);
                        if (latest != null && !result.contains(latest)) {
                            result.add(latest);
                        }
                        break;
                    }
                }
            }
        }
        return result;
    }

    private Observation latestArtifactRead(String path, AgentContext context) {
        File target = workspaceService.resolve(path);
        List<Observation> observations = context.getObservations();
        for (int index = observations.size() - 1; index >= 0; index--) {
            Observation observation = observations.get(index);
            if (observation.isSuccess() && observation.getInput() instanceof ToolInput
                    && Arrays.asList("write_file", "create_file", "patch_file", "edit_file").contains(observation.getTool())) {
                String changedPath = ((ToolInput) observation.getInput()).getPath();
                if (changedPath != null && target.equals(workspaceService.resolve(changedPath))) {
                    return null;
                }
            }
            if (!observation.isSuccess() || !"read_file".equals(observation.getTool())
                    || !(observation.getResult() instanceof FileContent)) {
                continue;
            }
            String readPath = ((FileContent) observation.getResult()).getPath();
            if (readPath == null || isTemplatePath(readPath)) {
                continue;
            }
            if (target.equals(workspaceService.resolve(readPath))) {
                return observation;
            }
        }
        return null;
    }

    private List<String> currentArtifactPaths(AgentContext context) {
        List<String> paths = new ArrayList<>();
        String current = currentTemplate(context);
        if (!contractValidator.isImplementationTemplate(current)) {
            return paths;
        }
        for (File pom : dependencySupport.pomFiles(context)) {
            paths.add(workspaceService.relativePath(pom));
        }
        String packagePath = null;
        String entryPath = null;
        for (Observation observation : context.getObservations()) {
            if (!observation.isSuccess() || !(observation.getResult() instanceof FileContent)) {
                continue;
            }
            FileContent content = (FileContent) observation.getResult();
            if (content.getPath() == null || content.getLines() == null
                    || !contractValidator.targetFileName(context).equals(new File(content.getPath()).getName())) {
                continue;
            }
            String source = String.join("\n", content.getLines()).replaceAll("(?m)^\\d+: ?", "");
            Matcher declaration = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(source);
            if (declaration.find()) {
                packagePath = declaration.group(1).replaceAll("\\.extension$", "").replace('.', '/');
                entryPath = workspaceService.relativePath(workspaceService.resolve(content.getPath()));
                break;
            }
        }
        for (String name : allowedFileNames(context, current)) {
            if ("pom.xml".equals(name)) {
                continue;
            }
            if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                paths.add("src/main/resources/" + name);
            } else if (name.equals(contractValidator.targetFileName(context))) {
                if (entryPath != null) {
                    paths.add(entryPath);
                }
            } else if (packagePath != null) {
                String directory = name.contains("Entity") ? "entity" : "ReadConfig.java".equals(name)
                        ? "config" : "AhiHttpInvokerHandler.java".equals(name) ? "handler" : "utils";
                paths.add("src/main/java/" + packagePath + "/" + directory + "/" + name);
            }
        }
        return paths;
    }

    private String buildArtifactRules(AgentContext context) {
        StringBuilder rules = new StringBuilder("\n当前模板标准产物路径（系统检查，不代表其它路径不存在）:\n");
        for (String path : currentArtifactPaths(context)) {
            rules.append(path).append(workspaceService.resolve(path).isFile()
                    ? " 已存在：基于读取内容使用 patch_file 局部修改或确认复用，禁止覆盖。\n"
                    : " 此路径不存在：先确认是否有其它位置的等价实现，无可复用实现才新建。\n");
        }
        return rules.toString();
    }

    public String currentTemplate(AgentContext context) {
        List<String> paths = new ArrayList<>(templatePaths(context.getTask()));
        // 总览优先，其余分块按编号顺序执行，不依赖 Skill 文本路径的出现顺序。
        paths.sort(Comparator.comparing(path -> path.contains("predict-call-template-parts/") ? "1" + path : "0" + path));
        for (String path : paths) {
            if (!context.getCompletedTemplates().contains(path)) {
                return path;
            }
        }
        return null;
    }

    public boolean hasTemplates(AgentContext context) {
        return !templatePaths(context.getTask()).isEmpty();
    }

    public void advanceAfterExecution(AgentContext context) {
        context.getTemplateErrors().clear();
        String current = currentTemplate(context);
        if (current == null || !hasReadTemplate(current, context.getObservations())) {
            return;
        }
        if (context.getLastObservations().stream().anyMatch(observation -> !observation.isSuccess())) {
            context.getTemplateErrors().add("本轮存在失败工具，请先纠正失败操作再完成当前模板");
            return;
        }
        List<String> errors = contractValidator.validate(current, context);
        if (!errors.isEmpty()) {
            context.getTemplateErrors().addAll(errors);
            log.info("S1 template pending path={} errors={}", current, errors);
            return;
        }
        // 只推进当前模板，下一个模板的读取在下一轮由系统直接构造。
        context.getCompletedTemplates().add(current);
        log.info("S1 template completed path={} completedCount={}", current, context.getCompletedTemplates().size());
    }

    public List<String> validateAll(AgentContext context) {
        List<String> errors = new ArrayList<>();
        for (String path : templatePaths(context.getTask())) {
            errors.addAll(contractValidator.validate(path, context));
        }
        return errors;
    }

    public String buildCurrentRules(AgentContext context) {
        String current = currentTemplate(context);
        if (current == null) {
            return "所有模板已通过产物结构检查。\n";
        }
        return "当前唯一实施模板: " + current + "\n已完成模板: " + context.getCompletedTemplates()
                + "\n先按当前模板完成全部产物（可分多轮写入），服务端检查通过后才切换下一模板。"
                + "禁止提前读取或实现后续模板；允许定位/读取当前产物、pom 依赖和必要上下文。"
                + "已有等价实现必须先 read_file 确认复用，禁止重复创建。"
                + "本次暂停 S2：禁止生成测试类、禁止运行测试。\n"
                + "当前模板允许产物: " + allowedFileNames(context, current)
                + buildArtifactRules(context)
                + (contractValidator.isImplementationTemplate(current) ? dependencySupport.buildRules(context, current) : "")
                + "\n尚缺结构: " + context.getTemplateErrors() + "\n";
    }

    /**
     * 执行前硬约束，防止一个计划读取未来模板或提前修改业务入口。
     */
    public void validateStep(AgentContext context, PlanStep step) {
        if (!(step.getInput() instanceof ToolInput)) {
            return;
        }
        String path = ((ToolInput) step.getInput()).getPath();
        if (path == null) {
            return;
        }
        String normalized = path.replace('\\', '/');
        boolean mutation = Arrays.asList("write_file", "create_file", "patch_file", "edit_file").contains(step.getTool());
        if (mutation && !testGenerationEnabled
                && (normalized.startsWith("src/test/") || normalized.contains("/src/test/"))) {
            throw new IllegalArgumentException("S2 已暂停，禁止修改测试文件: " + path);
        }
        if (mutation && isTemplatePath(path)) {
            throw new IllegalArgumentException("参考模板只读: " + path);
        }
        if (mutation && "pom.xml".equals(new File(path).getName()) && hasTemplates(context)) {
            File modulePom = dependencySupport.modulePom(context);
            if (modulePom == null || !modulePom.equals(workspaceService.resolve(path))) {
                throw new IllegalArgumentException("只能修改入口所属模块 POM，本地父 POM 与其它模块只读: " + path);
            }
        }
        if (context.getPhase() != AgentPhase.CODING || !hasTemplates(context)) {
            return;
        }
        String current = currentTemplate(context);
        if ("read_file".equals(step.getTool()) && isTemplatePath(path)) {
            if (current == null || !path.replace('\\', '/').endsWith(current)) {
                throw new IllegalArgumentException("当前仅允许读取模板: " + current);
            }
        }
        if (mutation && contractValidator.isImplementationTemplate(current)
                && !allowedFileNames(context, current).contains(new File(path).getName())) {
            throw new IllegalArgumentException("必须先完成当前模板 " + current
                    + "，当前允许产物: " + allowedFileNames(context, current));
        }
        if (mutation && current != null && !hasReadTemplate(current, context.getObservations())) {
            throw new IllegalArgumentException("必须先读取当前模板: " + current);
        }
    }

    private List<String> allowedFileNames(AgentContext context, String current) {
        if (current == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        names.add("pom.xml");
        if (current.contains("01-request-entity")) {
            names.addAll(Arrays.asList("AhiHttpBodyEntity.java", "AhiHttpBodyHeaderEntity.java", "AhiHttpBodyDataEntity.java"));
        } else if (current.contains("02-config-class")) {
            names.addAll(Arrays.asList("ReadConfig.java", "application.yml", "application.yaml"));
        } else if (current.contains("03-http-client-util")) {
            names.addAll(Arrays.asList("HttpClientUtil.java", "SpringContextBeanUtil.java"));
        } else if (current.contains("04-invoke-handler")) {
            names.add("AhiHttpInvokerHandler.java");
        } else if (current.contains("05-entry-and-mapping")) {
            names.add(contractValidator.targetFileName(context));
        }
        return names;
    }

    public List<Observation> templateObservations(AgentContext context) {
        List<Observation> result = new ArrayList<>();
        String current = currentTemplate(context);
        for (Observation observation : context.getObservations()) {
            if (!observation.isSuccess() || !(observation.getResult() instanceof FileContent)) {
                continue;
            }
            String path = ((FileContent) observation.getResult()).getPath();
            if (path != null && (current != null && path.replace('\\', '/').endsWith(current)
                    || path.endsWith("00-overview-and-constraints.md"))) {
                result.removeIf(previous -> ((FileContent) previous.getResult()).getPath().equals(path));
                result.add(observation);
            }
        }
        return result;
    }

    public Set<String> templatePaths(String task) {
        return extractTemplatePaths(skillMatcher.match(task));
    }

    public boolean hasReadTemplate(String path, List<Observation> observations) {
        if (path == null || observations == null) {
            return false;
        }
        String targetPath = path.trim().replace('\\', '/');
        for (Observation observation : observations) {
            if (!"read_file".equals(observation.getTool()) || !observation.isSuccess()
                    || !(observation.getResult() instanceof FileContent)) {
                continue;
            }
            String readPath = ((FileContent) observation.getResult()).getPath();
            if (readPath != null && readPath.replace('\\', '/').endsWith(targetPath)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsUnreadTemplateRead(CodePlan plan, List<Observation> observations) {
        if (plan == null || plan.getSteps() == null) {
            return false;
        }
        for (PlanStep step : plan.getSteps()) {
            if (!"read_file".equals(step.getTool()) || !(step.getInput() instanceof ToolInput)) {
                continue;
            }
            String path = ((ToolInput) step.getInput()).getPath();
            if (isTemplatePath(path) && !hasReadTemplate(path, observations)) {
                return true;
            }
        }
        return false;
    }

    public boolean isTemplatePath(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.trim().replace('\\', '/').toLowerCase();
        return normalized.startsWith("docs/")
                || normalized.contains("/docs/") && normalized.endsWith(".md");
    }

    public boolean isTemplatePart(Observation observation) {
        String path = observationPath(observation);
        return path != null && path.replace('\\', '/').toLowerCase()
                .contains("docs/predict-call-template-parts/");
    }

    public Observation findLatestTemplatePart(List<Observation> observations) {
        if (observations == null) {
            return null;
        }
        for (int i = observations.size() - 1; i >= 0; i--) {
            Observation observation = observations.get(i);
            if (observation != null && observation.isSuccess()
                    && "read_file".equals(observation.getTool()) && isTemplatePart(observation)) {
                return observation;
            }
        }
        return null;
    }

    private List<String> unreadTemplatePaths(String task, List<Observation> observations) {
        List<String> paths = new ArrayList<>();
        for (String path : extractTemplatePaths(skillMatcher.match(task))) {
            if (!hasReadTemplate(path, observations)) {
                paths.add(path);
            }
        }
        return paths;
    }

    private String selectNextTemplate(List<String> paths) {
        String firstChunk = null;
        for (String path : paths) {
            if (!path.contains("predict-call-template-parts/")) {
                return path;
            }
            if (path.contains("00-overview-and-constraints")) {
                return path;
            }
            if (firstChunk == null) {
                firstChunk = path;
            }
        }
        return firstChunk;
    }

    private List<PlanStep> singleTemplateRead(String path) {
        List<PlanStep> steps = new ArrayList<>();
        steps.add(PlanStep.builder().order(1).tool("read_file")
                .input(ToolInput.builder().path(path).startLine(1).endLine(1000).build())
                .description("Read skill reference template before coding: " + path).build());
        return steps;
    }

    private String observationPath(Observation observation) {
        if (observation == null || observation.getInput() == null) {
            return null;
        }
        if (observation.getInput() instanceof ToolInput) {
            return ((ToolInput) observation.getInput()).getPath();
        }
        return String.valueOf(observation.getInput());
    }

    private Set<String> extractTemplatePaths(List<Skill> skills) {
        Set<String> paths = new LinkedHashSet<>();
        if (skills == null) {
            return paths;
        }
        Pattern pattern = Pattern.compile("[`'\\\"]?(docs/[\\w./-]+\\.md)[`'\\\"]?");
        for (Skill skill : skills) {
            extractPaths(skill.getRules(), pattern, paths);
            extractPaths(skill.getImplementation(), pattern, paths);
            if (skill.getTemplate() != null) {
                extractText(skill.getTemplate(), pattern, paths);
            }
        }
        return paths;
    }

    private void extractPaths(List<String> texts, Pattern pattern, Set<String> paths) {
        if (texts != null) {
            for (String text : texts) {
                extractText(text, pattern, paths);
            }
        }
    }

    private void extractText(String text, Pattern pattern, Set<String> paths) {
        if (text == null) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
    }
}
