package com.cmbchina.llm.planner;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.FileOperationResult;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.verify.TestSourceInspector;
import com.cmbchina.llm.verify.VerifyResult;
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

    /** 仅将本次报告确认失败/缺少证据的测试类映射到任务清单，不从 Maven 摘要猜测失败文件。 */
    public List<String> failedTestPaths(AgentContext context, List<String> classNames) {
        Set<String> failed = new LinkedHashSet<>(classNames);
        List<String> paths = new ArrayList<>();
        for (Entry entry : state(context).entries) {
            if (failed.contains(entry.testClass)) {
                paths.add(entry.test);
            }
        }
        return paths;
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
                + testDependencyRules(context)
                + testIsolationRules()
                + "使用真实业务签名和已有 JUnit4/5、断言/Mockito；测试须有实际业务断言。"
                + "覆盖正常、异常、空值及本需求分支；HTTP/DB/私有服务使用 mock，不启动 Spring 全上下文或真实网络。"
                + "已有测试先读后局部 patch；禁止重写业务、空壳、恒定值断言、禁用、assume 或删除既有测试/断言。"
                + "缺测试依赖只局部补模块 POM test scope，版本复用已有管理；不能猜私有依赖坐标或版本。"
                + "使用 Mockito.mockStatic/mockConstruction 前核对实际 Mockito 版本及 MockMaker；"
                + "spring-boot-starter-test 或 mockito-core 的存在不代表支持静态/构造器 Mock。"
                + "Mockito 3/4 须启用 inline，缺失时局部补同现有 core 版本的 mockito-inline test 依赖，"
                + "版本优先复用已确认的 BOM 管理，不升级已有依赖；Mockito 5 默认 inline，仍须检查覆盖配置。"
                + "测试须先准备 SpringContextBeanUtil 的 Mock 和所需 Bean，再首次主动使用有静态 Bean 字段的业务类。"
                + "类初始化只执行一次，反射置空不会重新初始化；不要反射写 static final 字段或把生产依赖置空，"
                + "可变静态状态只注入正确 Mock，并在测试结束后恢复，不能修改生产初始化来迁就测试。"
                + "新增依赖使用已读 POM 中真实且唯一的 dependencies 结束标签作局部插入锚点；"
                + "不要为添加依赖重排已有 dependency/artifactId 的格式，不能拼造 oldText。"
                + "Maven 由服务端验证阶段执行，模型不得调用 run_test、shell、exec 或其他命令工具。\n";
    }

    public String testDependencyRules(AgentContext context) {
        return initializationFeedback(context) + dependencySupport.testDependencyRules(context)
                + (context.getPhase() == AgentPhase.REPAIR ? dependencySupport.repairDependencyRules(context) : "");
    }

    /** 优先解释本次已知根因，避免将后续类初始化失败误当成缺包或 Mockito 版本问题。 */
    private String initializationFeedback(AgentContext context) {
        String errors = String.join("\n", context.getVerifyErrors());
        StringBuilder rules = new StringBuilder();
        if (errors.contains("private") && errors.contains("SpringContextBeanUtil")
                && errors.contains("applicationContext")) {
            rules.append("本轮测试编译失败是直接访问 SpringContextBeanUtil 的 private applicationContext 字段。")
                    .append("先核对已读真实声明，保留字段 private 和全部测试/断言；setup/teardown 对可变静态字段的重置与恢复")
                    .append("可用 ReflectionTestUtils.setField(SpringContextBeanUtil.class, \"applicationContext\", 值)，")
                    .append("保存原值后在测试结束恢复，禁止写 final。")
                    .append("assertSame(expected, SpringContextBeanUtil.applicationContext) 必须保留相同 expected 及 assertSame，")
                    .append("仅将实际值替换为 ReflectionTestUtils.getField(SpringContextBeanUtil.class, \"applicationContext\")，")
                    .append("同时补 org.springframework.test.util.ReflectionTestUtils 的真实 import/已有 test 依赖依据。")
                    .append("不能删除该断言，也不能复制 getApplicationContext() 的另一条断言来凑数量。\n");
        }
        if (errors.contains("AbstractPipeline") && errors.contains("getApplicationContext")) {
            rules.append("本轮编译诊断已确认：AbstractPipeline 的 pipeline 变量没有 getApplicationContext()。")
                    .append("不要重复搜索该方法或猜其它 Pipeline getter，也不能修改依赖桩来补造 API。")
                    .append("当前业务模板已使用 SpringContextBeanUtil.getApplicationContext().getBean(ReadConfig.class)，")
                    .append("核对已固定 HttpClientUtil/Handler 源码中的真实 import 后，将入口 ReadConfig 的取 Bean 路径局部修正为该工具，")
                    .append("保留 super.init(pipeline) 与原有初始化逻辑。SpringContextBeanUtil 必要时按真实定义路径 read_file 核对，")
                    .append("不要因 .ai 引用目录被搜索忽略就重复同一 search_code。\n");
        }
        if (errors.contains("Max value may not be negative or zero")) {
            rules.append("本轮已确认根因：连接池容量为零或负数。ReadConfig 若为普通 Mockito Mock，")
                    .append("未 stub 的 int getter 默认返回0；先在首次初始化 HttpClientUtil/Handler 前设置真实源码所需的")
                    .append("getHttpMaxtotalConnections/getHttpMaxperrouteConnections 为正数，或使用完整配置夹具。")
                    .append("HttpClients.custom 的 Mock 只替换 Builder，不屏蔽静态块中的真实连接池。")
                    .append("后续 NoClassDefFoundError 是首次初始化失败的连锁结果，不是缺 JAR，不能删除生产初始化。\n");
        }
        if (errors.contains("Could not initialize class") || errors.contains("Cannot instrument class")) {
            rules.append("初始化失败修复必须追溯首次 Caused by，核对已固定的配置/HTTP工具/Handler 源码，")
                    .append("在首次主动使用前准备 Spring Bean、有效配置及公开工厂 Mock。")
                    .append("类静态字段在同一测试 JVM 只初始化一次；新一轮 BeforeEach 的 Bean Mock 不会自动替换缓存字段。")
                    .append("仅可基于真实源码保存/注入/恢复可变静态字段；禁止反射改写 final 字段。\n");
        }
        if (errors.contains("already registered") || errors.contains("InvalidUseOfMatchersException")
                || errors.contains("静态方法访问本类实例成员") || errors.contains("静态上下文")) {
            rules.append("本轮先检查 Mock 生命周期和第一个异常，而非调整业务：构造回调赋值的字段在真实构造前仍为 null，")
                    .append("对 null 调用 when(field.method(any())) 会同时导致 NPE 和 matcher 残留；桩直接放在回调参数 mock 上。")
                    .append("静态 Mock 重复注册优先检查 setup 异常路径和 teardown 是否关闭全部句柄，")
                    .append("不能把 BeforeEach/AfterEach 直接换成 static BeforeAll/AfterAll 或把全部状态静态化。\n");
        }
        if ((errors.contains("expected: <1> but was: <0>") || errors.contains("WantedButNotInvoked"))
                && (errors.contains("HttpClientUtil") || errors.contains("AhiHttpInvokerHandler"))) {
            rules.append("本轮先核对已固定源码中的静态缓存：Handler 的 static final httpClientUtil 与工具类的连接池只创建一次，")
                    .append("后续 new Handler/new HttpClientUtil 不会再次触发这些静态构造，不能要求每轮 constructed().size()==1。")
                    .append("只读取得缓存对象可核对实际引用，禁止反射写 final；优先在真实公开依赖层隔离，")
                    .append("或采用明确且跨用例可靠的类级夹具复用已确认 Mock，不能靠重复注册构造器 Mock 替换已缓存对象。")
                    .append("HttpClientUtil 的 readConfig/beeTraceClientHelper 是可变静态引用；每轮新的 Bean Mock 不会替换旧引用，")
                    .append("基于真实字段保存、注入本轮依赖并在关闭句柄前恢复，verify 必须作用于业务实际使用的 Mock。")
                    .append("保留业务返回、异常和 HTTP 参数断言，不把删除交互验证或修改生产 final 当作修复。\n");
        }
        return rules.toString();
    }

    public String testIsolationRules() {
        return "测试隔离必须依据真实可访问类型及实际创建路径：禁止 import 或全限定引用包私有 "
                + "InternalHttpClient，全限定名不能绕过访问控制；禁止对抽象 CloseableHttpClient 做构造器 Mock。"
                + "HttpClientUtil 调用 HttpClients.custom()，可 mockStatic(HttpClients.class)，"
                + "返回 mock(HttpClientBuilder.class, RETURNS_SELF)，让 builder.build() 返回普通 "
                + "mock(CloseableHttpClient.class)；单独拦截不在实际创建链上的构造器无效。"
                + "公开工厂 Mock 与 SpringContextBeanUtil 及全部所需 Bean 必须在业务类首次初始化前就绪；"
                + "mockConstruction(业务类) 也会触发类初始化，不自动屏蔽静态字段/静态块。"
                + "HTTP 初始化上下文已按真实依赖固定 ReadConfig/HttpClientUtil/Handler 源码，先核对静态块和构造器。"
                + "普通 Mock 的 int/boolean getter 默认0/false，真实连接池需要正数的 maxTotal/maxPerRoute；"
                + "在首次初始化前明确 stub getHttpMaxtotalConnections/getHttpMaxperrouteConnections，"
                + "或用完整 ReadConfig 夹具配置 URL、超时、重试、连接池容量与空闲时间。"
                + "只 Mock HttpClients.custom 不会阻止静态块创建连接池，不能用默认0配置。"
                + "MockedStatic 工厂句柄应在字段保存到 AfterEach/After 关闭，或在同一测试 try 内覆盖初始化、"
                + "调用、验证；不能在 BeforeEach 的 finally 或局部 try 结束时提前关闭后再在测试中触发构造。"
                + "业务可变静态 Bean/配置/追踪字段会跨方法和测试类缓存；每次测试按真实字段保存、注入本轮 Mock 并恢复，"
                + "单纯换 Spring Bean Mock 不会让已初始化类再次取 Bean，禁止反射写 static final。"
                + "HttpClient4 协议常量为 HttpVersion.HTTP_1_1，ProtocolVersion 没有 HTTP_1_1/HTTP_1_0；"
                + "也可 new ProtocolVersion(\"HTTP\", 1, 1)。新增类型用法必须同时补真实 import。"
                + "Mockito stub/verify 涉及声明 IOException 的 execute/close 等 API 时，测试方法也需捕获或声明异常；"
                + "assertThrows lambda 外的 stub 不会被断言代为捕获，不能删除异常断言来消除编译错误。"
                + "constructed() 只包含已实际构造的对象；不要在被测调用之前取 constructed().get(0)，"
                + "应在 mockConstruction 的初始化回调中设置 doPost 等桩，业务调用之后再验证构造结果。"
                + "开关关闭等不应构造对象的分支应验证未构造，不能人为 new 业务类填充 constructed()。"
                + "在构造回调中赋值给字段不会立即执行回调；业务构造前该字段仍为 null，不能 when(字段.doPost(any()))。"
                + "不同分支的返回/抛异常行为在本用例注册的构造回调参数 mock 上配置，业务执行后再取 constructed() 验证。"
                + "禁止反射改写 AhiHttpInvokerHandler 的 static final httpClientUtil/REQUEST_URL，"
                + "应在首次初始化前控制公开工厂或业务构造链；不能删除生产 final 或 Bean 初始化。"
                + "MockedStatic/MockedConstruction 同线程同类型不得重复注册；使用 try-with-resources "
                + "或可靠的清理钩子关闭，禁止类级、setUp 和方法级对同一类型重叠注册。"
                + "BeforeEach/AfterEach 或 Before/After 使用实例方法，不能为掩盖注册冲突换成类级钩子。"
                + "确需 JUnit5 类级夹具时核对 TestInstance(PER_CLASS) 或一致的 static 成员访问及状态隔离；"
                + "不能只将 setup/teardown 加 static 却保留实例字段，也不能把所有测试状态静态化。"
                + "setup 中途失败也必须释放已注册句柄；teardown 通过 try/finally 等方式独立关闭每个句柄，"
                + "不能第一个 close 抛错就跳过其余关闭；每次关闭后清空句柄，避免重复关闭和跨用例泄漏。"
                + "static final 缓存不会在每轮构造器 Mock 注册后重新生成，不能假定新回调可替换已缓存对象；"
                + "按真实创建链控制公开依赖，保持生产 final 与初始化契约。"
                + "构造器 Mock 回调直接 stub 回调给出的 mock；不能在尚未完成的 when(...).thenReturn(...) "
                + "链中创建另一个 Mock 或触发构造器回调里的 stub，应先准备依赖对象，再独立登记返回值，"
                + "避免把 ReadConfig 返回值登记到返回 Boolean 的 getAhiHttpInvokeEnable 上。"
                + "EventData 等公共业务类型按已读真实 API 使用，不能猜测 setData；"
                + "缺 setter 时使用真实可变集合 API 或 stub getData()，不为测试篡改公共类型。"
                + "空值分支按真实异常契约断言，不要仅凭测试名猜测 assertDoesNotThrow。"
                + "仅当本次用例报告 Unexpected exception thrown、源码明确声明并抛出同一异常时，"
                + "可将同一被测调用的 assertDoesNotThrow 更正为该具体类型的 assertThrows；"
                + "其它既有断言和交互验证仍须逐字保留，不得顺便修改返回值预期。"
                + "任何修复必须保留业务断言，不能通过跳过测试、调用真实网络或改生产契约迁就测试。\n";
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
                    throw new IllegalArgumentException("S2 修复不得破坏 S1 公共追踪契约: " + errors
                            + "；Mockito MockMaker 报错先修复测试依赖与配置；初始化问题先修复测试 Mock 顺序及状态恢复。"
                            + "禁止删除生产 Bean/静态初始化，或将生产配置、URL、Client 置空来通过测试。");
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
                    inspector.preserveAssertions(before, after, inspector.read(workspaceService.resolve(entry.source)),
                            entry.testClass, context.getVerifyErrors());
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
            dependencySupport.validateTestPom(context, before, after,
                    context.getPhase() != AgentPhase.REPAIR, missingVersions);
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
        if (context.getPhase() == AgentPhase.REPAIR) {
            String errors = String.join("\n", context.getVerifyErrors());
            Object verification = context.getVariables().get("verify_result");
            List<String> failedFiles = verification instanceof VerifyResult
                    ? ((VerifyResult) verification).getFailedFiles() : Collections.emptyList();
            String repairFeedback = context.getVerifyErrors().stream().filter(error ->
                    error.startsWith("上一计划被拒绝") || error.startsWith("修复工具执行失败:"))
                    .collect(java.util.stream.Collectors.joining("\n"));
            // 仅固定本次错误点名的清单文件，避免修复阶段只剩 POM、反复猜测实体和调用方。
            for (Entry candidate : state.entries) {
                boolean involved = failedFiles.isEmpty()
                        ? errors.contains(candidate.sourceClass) || errors.contains(candidate.testClass)
                        : failedFiles.contains(candidate.test) || repairFeedback.contains(candidate.source)
                        || repairFeedback.contains(candidate.test);
                if (involved) {
                    if (!paths.contains(candidate.source)) {
                        paths.add(candidate.source);
                    }
                    if (!paths.contains(candidate.test)) {
                        paths.add(candidate.test);
                    }
                }
            }
        }
        addInitializationSources(state, paths);
        for (File pom : dependencySupport.pomFiles(context)) {
            paths.add(workspaceService.relativePath(pom));
        }
        return paths;
    }

    /** 只固定本次清单中 HTTP 创建链的最多三个支持类，不扫描工程或读取其它模板。 */
    private void addInitializationSources(State state, List<String> paths) {
        boolean http = false;
        for (Entry entry : state.entries) {
            if (!paths.contains(entry.source)) {
                continue;
            }
            CompilationUnit unit = inspector.parse(inspector.read(workspaceService.resolve(entry.source)));
            if (unit.findAll(com.github.javaparser.ast.expr.SimpleName.class).stream().anyMatch(name ->
                    "HttpClientUtil".equals(name.asString()) || "AhiHttpInvokerHandler".equals(name.asString()))) {
                http = true;
                break;
            }
        }
        if (http) {
            for (String name : Arrays.asList("ReadConfig", "HttpClientUtil", "AhiHttpInvokerHandler")) {
                for (Entry entry : state.entries) {
                    if (name.equals(entry.sourceClass) && !paths.contains(entry.source)) {
                        paths.add(entry.source);
                        break;
                    }
                }
            }
        }
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
