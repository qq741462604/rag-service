package com.cmbchina.llm.planner;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.FileOperationResult;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.verify.TestSourceInspector;
import com.cmbchina.llm.skill.SkillManager;
import com.cmbchina.llm.verify.VerifyResult;
import com.cmbchina.llm.workspace.WorkspaceService;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
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
    private final SkillManager skillManager;
    private final BusinessSkillContractValidator businessContracts;

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
        for (Entry entry : state.entries) {
            state.referenceSources.put(entry.sourceClass, entry.source);
        }
        if (businessContracts.supports(context)) {
            Set<String> ambiguous = new LinkedHashSet<>();
            for (String path : businessContracts.artifactPaths(context)) {
                File file = workspaceService.resolve(path);
                if (!file.getName().endsWith(".java") || !file.toPath().startsWith(sourceRoot.toPath())) {
                    continue;
                }
                CompilationUnit unit = inspector.parse(inspector.read(file));
                for (com.github.javaparser.ast.body.TypeDeclaration<?> type : unit.getTypes()) {
                    String old = state.referenceSources.putIfAbsent(type.getNameAsString(), path);
                    if (old != null && !old.equals(path)) {
                        ambiguous.add(type.getNameAsString());
                    }
                }
            }
            ambiguous.forEach(state.referenceSources::remove);
        }
        if (state.entries.isEmpty()) {
            throw new IllegalArgumentException("S2 没有可执行测试条目；跳过原因=" + state.skipped);
        }
        context.getVariables().put(STATE, state);
        log.info("S2流程 flow={} 建立测试清单 module={} entries={} skipped={}",
                context.getVariables().get("s2_flow_id"), state.pom, state.entries.size(), state.skipped);
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
        if (!steps.isEmpty()) {
            log.info("S2流程 flow={} round={} 读取最新文件 paths={}", context.getVariables().get("s2_flow_id"),
                    context.getVariables().get("s2_repair_round"), steps.stream()
                            .map(step -> ((ToolInput) step.getInput()).getPath()).collect(java.util.stream.Collectors.toList()));
        }
        return steps.isEmpty() ? null : CodePlan.builder().task(context.getTask()).steps(steps).build();
    }

    public boolean advance(AgentContext context) {
        State state = state(context);
        Entry entry = current(state);
        if (entry == null) {
            return true;
        }
        List<String> errors = validate(state, entry);
        if (freshRead(context, entry.source) == null
                || workspaceService.resolve(entry.test).isFile() && freshRead(context, entry.test) == null) {
            return false;
        }
        if (!errors.isEmpty()) {
            return false;
        }
        entry.complete = true;
        log.info("S2流程 flow={} 测试源码通过预检 source={} test={} progress={}/{}，尚未执行 Maven",
                context.getVariables().get("s2_flow_id"), entry.source, entry.test,
                state.entries.stream().filter(value -> value.complete).count(), state.entries.size());
        return current(state) == null;
    }

    public List<String> validateAll(AgentContext context) {
        State state = state(context);
        List<String> errors = new ArrayList<>();
        for (Entry entry : state.entries) {
            errors.addAll(validate(state, entry));
        }
        if (errors.isEmpty() && state.entries.stream().anyMatch(entry -> inspector.requiresInlineMocking(
                inspector.read(workspaceService.resolve(entry.test))))) {
            errors.addAll(dependencySupport.validateMockitoCapability(context, Collections.emptyMap()));
        }
        return errors;
    }

    private List<String> validate(State state, Entry entry) {
        try {
            List<String> errors = validateSource(state, entry, inspector.read(workspaceService.resolve(entry.test)));
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
                + "当前测试文件不存在时，直接按真实业务源码生成该路径的新测试，不要尝试读取它或其它尚未生成的测试。\n"
                + "当前未满足项: " + (entry == null ? "无" : validate(state, entry)) + "\n"
                + skillManager.buildAcceptanceContext(context.getSkills(), entry == null ? null : entry.sourceClass)
                + "逐项将命中 Skill 的 Acceptance 转为真实输入、调用和结果/交互断言；测试方法名或有断言不代表业务验收已覆盖。"
                + "同一入口命中多个 Skill 时全部覆盖；测试失败保留原断言强度，先核对并修复真实业务实现。\n"
                + testDependencyRules(context)
                + testIsolationRules()
                + "使用真实业务签名和已有 JUnit4/5、断言/Mockito；测试须有实际业务断言。"
                + "初始化测试保留对象非空断言，并补初始化后的状态或依赖交互，不能只断言 new 的对象非空。"
                + "固定评分输入用具体 BigDecimal 期望验证写回，不仅使用 any(BigDecimal.class)；"
                + "HTTP 头和参数测试捕获实际 HttpUriRequest 验证，不仅断言响应桩的返回常量。"
                + "覆盖正常、异常、空值及本需求分支；HTTP/DB/私有服务使用 mock，不启动 Spring 全上下文或真实网络。"
                + responseCoverageRules(context)
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
                + (context.getPhase() == AgentPhase.REPAIR
                ? skillManager.buildAcceptanceContext(context.getSkills(), null) : "")
                + "请求 JSON 必须逐字使用当前实体 @JsonProperty 的键名及大小写，不能将 requestHeader/requestData "
                + "猜成 response_header 风格；当前真实映射（规范化名称=协议键）: " + requestJsonProperties(state(context)) + "。\n"
                + "默认 Lombok @Data/@ToString 的 toString 使用 Java 字段名，@JsonProperty 不会把它改成协议键。"
                + "验证 JSON 键必须使用真实 ObjectMapper 序列化结果；toString 的 contains 检查只使用真实 Java 字段名。"
                + "系统明确识别此类错误时，只允许保留原对象/assertTrue/contains 并纠正对应字段名，"
                + "不能删除断言、修改其它预期或新增生产 toString 来迎合测试。\n"
                + (context.getPhase() == AgentPhase.REPAIR ? dependencySupport.repairDependencyRules(context) : "");
    }

    /** 优先解释本次已知根因，避免将后续类初始化失败误当成缺包或 Mockito 版本问题。 */
    private String initializationFeedback(AgentContext context) {
        String errors = String.join("\n", context.getVerifyErrors());
        StringBuilder rules = new StringBuilder();
        if (errors.contains("Bsr_Opr_Dat") || errors.contains("动态日期断言")) {
            rules.append("经办日不是 expDat。核对当前 Handler 的上海时区日期来源，保留原 assertEquals 和实际值/消息，")
                    .append("预期改用同一 root 的 header.get(\"requestTime\").asText().substring(0, 8)；")
                    .append("保留 root/header/data 的声明及初值；header/data 分别从 request_header/request_data 读取。")
                    .append("源码证明动态来源时门禁认可该精确修正，不能改 Bsr_Ept_Dat 或硬编码今天。\n");
        }
        if (errors.contains("静态 Bean 隔离") || errors.contains("初始化交互边界")) {
            rules.append("逐项修复真实 Handler 和 HttpClientUtil 的可变静态 Bean：先准备 Spring Bean/工厂，")
                    .append("保存旧字段、注入本轮配置与 tracer，AfterEach finally 恢复旧值（即使旧值为 null）。")
                    .append("使用 ReflectionTestUtils.getField/setField，不能只替换缓存实例 httpClient。")
                    .append("验证 init 获取 Bean 次数时，在调用 init 前先对已有 Spring MockedStatic 句柄 clearInvocations()，")
                    .append("保留原精确 verify；不能 reset、改 times 或使用 atLeastOnce。\n");
        }
        if (errors.contains("模板响应测试缺少可见证据")) {
            rules.append(responseCoverageRules(context));
        }
        if (errors.contains("StringEntity") && errors.contains("getValue")) {
            rules.append("本轮编译错误是 Apache StringEntity 没有 getValue，不是 Header.getValue 不可用。")
                    .append("原 ((StringEntity) 实体表达式).getValue() 在原位置改为 EntityUtils.toString(同一实体表达式, Consts.UTF_8)，")
                    .append("补 import org.apache.http.util.EntityUtils 和 org.apache.http.Consts，必要时保留 throws Exception。")
                    .append("断言中的读取直接替换原实际值，保持原 assertEquals 预期、消息、捕获变量声明/初值及其它验证；")
                    .append("保留原 StringEntity 转换，或保留已有的同实体 instanceof StringEntity 断言后去掉多余转换。")
                    .append("系统支持这一真实类型明确的 API 修正，不引入正文别名/helper，不使用实体自身 toString()。")
                    .append("同时核对 HttpClientUtilTest、DataPrepEventHandlerTest 和 AhiHttpInvokerHandlerTest 的所有同类调用，")
                    .append("局部变量初始化中的正文读取也要修复，不能改生产代码或删除请求体检查。\n");
        }
        if (errors.contains("expected: <application/json> but was: <application/json; charset=UTF-8>")) {
            rules.append("本轮 Content-Type 失败先核对真实 Handler：ContentType.APPLICATION_JSON.toString() 包含 UTF-8 字符集。")
                    .append("源码确认后，仅把失败用例 assertEquals 的第一个裸 MIME 预期改成 ")
                    .append("ContentType.APPLICATION_JSON.toString() 并补 import org.apache.http.entity.ContentType，")
                    .append("或完整字符串 application/json; charset=UTF-8。系统支持这一有依据的精确预期纠正；")
                    .append("保留原 getFirstHeader(HTTP.CONTENT_TYPE).getValue()、捕获变量声明及初值、消息和全部其它断言/验证。")
                    .append("同一问题涉及入口与 Handler 两个测试，逐一修复；不能改用 contains/startsWith、提取 MIME、")
                    .append("删除字符集检查或修改生产头部来迎合错误预期。\n");
        }
        if (errors.contains("getEntity") && errors.contains("HttpUriRequest")) {
            rules.append("本轮 HttpUriRequest.getEntity 编译错误只需修正请求体访问，保留原捕获对象和全部断言。")
                    .append("HttpUriRequest 没有 getEntity，实体接口为 org.apache.http.HttpEntityEnclosingRequest。")
                    .append("保留 captured 的声明及 requestCaptor.getValue() 初值；直接在原断言的实际值位置使用 ")
                    .append("captured instanceof HttpEntityEnclosingRequest ? ((HttpEntityEnclosingRequest) captured).getEntity() : null，")
                    .append("补齐真实 import。无实体分支 build 可能返回不实现实体接口的请求，不能盲目强转。")
                    .append("门禁支持这一同对象、同 getEntity、非实体返回 null 的精确修正；")
                    .append("保持 assertNotNull/assertEquals 的原类型、null/非空预期及其它参数不变，")
                    .append("不新增别名或 helper 替换原验证，不改业务或改为更弱的断言。\n");
        }
        if (errors.contains("toString") && (errors.contains("request_header") || errors.contains("Lombok"))) {
            rules.append("本轮实体断言把 JSON 协议键与 Lombok 调试文本混用。先核对实体字段及 @JsonProperty；")
                    .append("默认 @Data 的 requestHeader 在 toString 中仍名为 requestHeader，不是 request_header。")
                    .append("对于系统确认的同一实体局部变量，仅把原 assertTrue(receiver.toString().contains(...)) ")
                    .append("的协议键字符串纠正为实际 Java 字段名，其余接收者、断言形式及验证保持原样，")
                    .append("门禁支持这一精确纠正；不要改实体、删除断言或用常量代替实际值。")
                    .append("JSON 序列化断言仍保留 request_header 等协议键。\n");
        }
        if (errors.contains("EventData") && errors.contains("setData")) {
            rules.append("本次编译已确认 EventData 没有 setData(Map)，不能继续调用或搜索该缺失 setter。")
                    .append("真实 EventData 实例的非空数据夹具使用 eventData.getData().putAll(eventFields)，保留原 Map 内容及全部断言；")
                    .append("若测试必须让 getData 返回 null 或某个特殊 Map，改用 mock(EventData.class) 并 when(eventData.getData()).thenReturn(原值)。")
                    .append("null 与空 Map 不能互换；有结果读取断言时让 putDataAndOriginData 的 Mock Answer 按真实 API 写回同一个结果集合，")
                    .append("或保留真实实例并用已确认的测试隔离方法，不凭空补 setter，不改公共包源码。")
                    .append("一次检查当前文件全部 setData 调用，包括后半文件；每个补丁使用所属方法的唯一上下文，")
                    .append("成功补读的准确范围已加入 Observations，不要重复相同 read_file。\n");
        }
        if (errors.contains("DataPrepEventHandler") && errors.contains("doPost")
                && (errors.contains("ArgumentsAreDifferent") || errors.contains("Argument(s) are different"))) {
            rules.append("入口 doPost 参数不一致时，先核对真实 ahiInvoke 从 eventData.getData() 读取数据的路径。")
                    .append("仅创建并填充 eventFields 不会改变 mock(EventData.class) 的 getData 返回值；")
                    .append("在被测调用前用 when(eventData.getData()).thenReturn(eventFields) 绑定本用例的原 Map，")
                    .append("或核对当前已有 Answer/夹具的实际返回对象；逐个检查当前文件的成功、异常和空响应分支。")
                    .append("保留原 Map、IP、doPost 参数及写回验证，不把具体值换成 anyMap/anyString/空 Map，")
                    .append("不修改生产默认值或异常处理来迁就测试。用例预期异常的日志仅作辅助定位，")
                    .append("测试是否失败以本次报告中的 failure/error 为准。\n");
        }
        if (errors.contains("无法转换") || errors.contains("incompatible types") || errors.contains("ReflectionTestUtils")) {
            rules.append("Spring ReflectionTestUtils.getField 的返回类型固定是 Object，不支持按左值自动推断泛型。")
                    .append("按真实字段类型显式转换：HttpClientUtil cached = (HttpClientUtil) ReflectionTestUtils.getField")
                    .append("(AhiHttpInvokerHandler.class, \"httpClientUtil\")；实例 httpClient 读取也须转为 CloseableHttpClient。")
                    .append("只读获取缓存后，仅保存/注入/恢复 cached 内部非 final 的 httpClient；")
                    .append("禁止再 setField(AhiHttpInvokerHandler.class, \"httpClientUtil\", cached)，写回相同对象仍是写 final。\n");
        }
        if (errors.contains("AhiHttpInvokerHandlerTest") && (errors.contains("expected: not <null>")
                || errors.contains("CommonException") || errors.contains("WrongTypeOfReturnValue"))) {
            rules.append("Handler 成功响应为空时先检查该失败用例的执行日志与真实客户端身份，不能只改响应体桩。")
                    .append("AhiHttpInvokerHandler.httpClientUtil 为 static final，可能已由先执行的入口测试初始化；")
                    .append("当前 BeforeEach 的 new HttpClientUtil 和新 HttpClients 工厂 Mock 不会替换该缓存。")
                    .append("在 Bean/工厂就绪后只读获取该缓存工具引用，保存其非 final httpClient 实例字段，")
                    .append("将本用例 closeableHttpClient 注入这个真实缓存实例，结束后恢复；")
                    .append("HttpClientUtil.readConfig/beeTraceClientHelper 的可变静态引用也按实际字段保存、注入、恢复。")
                    .append("用 verify(本轮客户端).execute(any(HttpUriRequest.class)) 确认桩被业务调用，不能手工调用 Mock 凑验证。")
                    .append("响应实体优先用真实 StringEntity(responseJson.toString(), Consts.UTF_8)，")
                    .append("EntityUtils.toString 会读取内容流，HttpEntity.toString 桩无效；")
                    .append("没有活动 mockStatic(EntityUtils.class) 不能 when(EntityUtils.toString(...))，否则真实方法会执行并产生错误返回类型登记。")
                    .append("保留全部成功响应断言和空响应/非200分支，不能将 assertNotNull 改为 assertNull 或删除断言。\n");
        }
        if (errors.contains("protected") && (errors.contains("buildRequestBody") || errors.contains("getUUID"))) {
            rules.append("跨包测试访问 protected 方法是测试组织问题，不能将生产方法改 public。")
                    .append("按真实声明添加测试侧子类，以同签名 public override 直接 super 转发，测试变量使用该子类。")
                    .append("保留断言内原方法调用、参数、预期与断言类型；不要复制被测生产实现。\n");
        }
        if (errors.contains("RequestBuilder") && errors.contains("execute")) {
            rules.append("本轮 HttpClient4 的真实 execute 单参数类型为 HttpUriRequest，RequestBuilder 只是构建器，")
                    .append("生产调用为 httpClient.execute(builder.build())。全部 stub 和 verify 同步改为 any(HttpUriRequest.class)，")
                    .append("补 org.apache.http.client.methods.HttpUriRequest 的 import；不需要 HttpHost。")
                    .append("该有实际源码依据的类型修正已受门禁支持，原 Mock、方法、次数及其它业务断言必须保持。")
                    .append("禁止 (HttpUriRequest) any() 强转、裸 any() 或修改生产签名来绕过错误。\n");
        }
        if (errors.contains("already registered") || errors.contains("Mock 句柄未关闭就被置空")) {
            rules.append("先检查最早泄漏源：MockedConstruction/MockedStatic 字段赋 null 不会关闭 Mock，")
                    .append("尤其成功分支把构造句柄清空会污染后续全部测试类。保留句柄并独立 finally 清理；")
                    .append("成功/错误响应各自使用 close 旧句柄后注册带回调的局部桩，默认仍返回 null，避免破坏空响应测试。")
                    .append("构造拦截本身正常时，后续 buildRequestBody/getUUID 返回 null 可能是泄漏的 Mock，不要改业务返回值。\n");
        }
        if (errors.contains("expected: <1> but was: <0>") || errors.contains("减少有效断言")) {
            rules.append("关闭分支若源码在 new AhiHttpInvokerHandler 前写 ahiResultAvailable=2 并 return，构造数应为0。")
                    .append("保留结果=2的断言；可把错误的 assertEquals(1, handle.constructed().size()) 和")
                    .append(" verify(handle.constructed().get(0), times(0)).doPost(...) 合并为一条")
                    .append(" assertTrue(handle.constructed().isEmpty()) 或 assertEquals(0, handle.constructed().size())。")
                    .append("系统仅在真实关闭条件/提前返回及同一构造句柄均确认时认可此合并，禁止其它分支随意删断言。\n");
        }
        if (errors.contains("NullPointerException") && errors.contains("AhiHttpInvokerHandlerTest")) {
            rules.append("本轮 Handler 测试 NPE 必须先核对失败行：类的无关纯函数也全部返回 null 时先排查上一个套件的构造 Mock 泄漏；")
                    .append("仅 JSON getAsJsonObject/get 后为空时核对键名，")
                    .append("请求键来自已固定的三个请求实体 @JsonProperty，不能套用响应的 response_header/response_data。")
                    .append("只有栈指向初始化/<clinit> 或存在初始化异常证据才修 Bean；Class.forName 不会修复 JSON 键名。")
                    .append("请求体 assertEquals 实际值中的错误键名可按实体注解等价纠正，保持预期值及断言类型不变。\n");
        }
        if (errors.contains("NullPointerException") && errors.contains("DataPrepEventHandlerTest")) {
            rules.append("入口调用开关是可空 Boolean；null 应按关闭处理。核对 verifyInvokeCondition 是否先拆箱成 boolean，")
                    .append("若是，局部修生产条件为 Boolean.TRUE.equals(readConfig.getAhiHttpInvokeEnable())，保留 null 测试。\n");
        }
        if (errors.contains("expected: <0> but was: <true>")) {
            rules.append("assertEquals(\"0\", String.valueOf(eventFields.size() >= 0)) 必然失败且未验证业务。")
                    .append("保留测试方法，改为调用真实 ahiInvoke/handle，通过 Mock 隔离外调并 verify(eventData).putDataAndOriginData")
                    .append("(\"ahiResultAvailable\", \"1\") 验证真实成功结果；不得在覆写 ahiInvoke 中自行写回再验证自己。")
                    .append("此类静态已证明必败的断言可在门禁阶段替换，必须实际调用真实 handle/ahiInvoke 并新增上述验证，")
                    .append("不能删除其它有效断言、只改预期为 true 或补恒定断言。\n");
        }
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
                    .append("容量 stub 必须在正常 setup、首次 mockConstruction 之前生效；catch 中 tearDown 只是失败清理，")
                    .append("不能代替夹具准备，测试方法内读取 getter 也不等于共享 setup 已完成 stub。")
                    .append("同时检查 Handler 首次初始化的 static final REQUEST_URL；若真实源码读取 getAhiHttpBusinessUrl，")
                    .append("须在首次构造器 Mock 前配置有效地址，复用已有 Handler 测试 URL，避免将默认 null 固化。")
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
                + "HTTP 头部预期取真实源码；ContentType.APPLICATION_JSON.toString() 包含 charset=UTF-8，"
                + "验证完整 getFirstHeader(...).getValue() 时须用同一常量或对应完整字符串，不能误用裸 application/json。"
                + "HttpClientUtil 调用 HttpClients.custom()，可 mockStatic(HttpClients.class)，"
                + "返回 mock(HttpClientBuilder.class, RETURNS_SELF)，让 builder.build() 返回普通 "
                + "mock(CloseableHttpClient.class)；单独拦截不在实际创建链上的构造器无效。"
                + "公开工厂 Mock 与 SpringContextBeanUtil 及全部所需 Bean 必须在业务类首次初始化前就绪；"
                + "mockConstruction(业务类) 也会触发类初始化，不自动屏蔽静态字段/静态块。"
                + "HTTP 初始化上下文已按真实依赖固定 ReadConfig/HttpClientUtil/Handler 源码，先核对静态块和构造器。"
                + "普通 Mock 的 int/boolean getter 默认0/false，真实连接池需要正数的 maxTotal/maxPerRoute；"
                + "在首次初始化前明确 stub getHttpMaxtotalConnections/getHttpMaxperrouteConnections，"
                + "或用完整 ReadConfig 夹具配置 URL、超时、重试、连接池容量与空闲时间。"
                + "真实源码若通过 getAhiHttpBusinessUrl 初始化 static final REQUEST_URL，必须在首次构造器 Mock 前"
                + "准备有效地址，复用同任务已有 Handler 测试 URL；后续更换 Bean 不会更新该 final 缓存。"
                + "只 Mock HttpClients.custom 不会阻止静态块创建连接池，不能用默认0配置。"
                + "MockedStatic 工厂句柄应在字段保存到 AfterEach/After 关闭，或在同一测试 try 内覆盖初始化、"
                + "调用、验证；不能在 BeforeEach 的 finally 或局部 try 结束时提前关闭后再在测试中触发构造。"
                + "业务可变静态 Bean/配置/追踪字段会跨方法和测试类缓存；每次测试按真实字段保存、注入本轮 Mock 并恢复，"
                + "HttpClientUtil.init() 才设置连接池容量；类已初始化后仅 new HttpClientUtil 不会再次调用 init，"
                + "验证容量配置时显式调用真实 init 并检查连接池结果，不要求每次构造都重新调用这些 getter。"
                + "构造 Mock 的回调必须把业务真正使用的对象交给桩/验证；另建 mock(HttpClientUtil.class) 不会替换 Handler 已缓存的 final 工具。"
                + "可只读取得 final HttpClientUtil 引用，保存/替换/恢复它的非 final httpClient 实例字段，"
                + "Handler 可能已由其它测试类初始化；每次新建工具或 Mock 工厂不会更新缓存，必须让 execute 桩作用于缓存中实际使用的客户端。"
                + "ReflectionTestUtils.getField 返回 Object，赋给 HttpClientUtil/CloseableHttpClient 等具体类型必须显式转换；"
                + "只读缓存不需要也不允许将同一缓存引用 setField 写回 static final 字段。"
                + "响应 JSON 优先封装真实 StringEntity(json, Consts.UTF_8)，不对 HttpEntity.toString 配桩，"
                + "没有活动静态 Mock 时不能 when(EntityUtils.toString(...))。"
                + "逐个保存/注入/恢复 Handler 和 HttpClientUtil 实际使用的可变静态 ReadConfig/BeeTraceClientHelper，"
                + "旧值为 null 也须恢复；辅助子类或 closeStaticMocks helper 不免除隔离要求，不能改 final 引用。"
                + "验证 init 的精确 Spring 调用次数前，用同一 MockedStatic 句柄 clearInvocations() 清除 setup 的初始化交互，"
                + "紧接着调用真实 init，再保留原 verify；禁止 reset 删除桩或改成 atLeastOnce。"
                + "经办日验证与同一请求 request_header.requestTime 的日期一致，不能把 Bsr_Opr_Dat 硬编码；"
                + "Bsr_Ept_Dat 有 expDat 输入时仍断言输入，不要同时改掉。"
                + "单纯换 Spring Bean Mock 不会让已初始化类再次取 Bean，禁止反射写 static final。"
                + "HttpClient4 协议常量为 HttpVersion.HTTP_1_1，ProtocolVersion 没有 HTTP_1_1/HTTP_1_0；"
                + "也可 new ProtocolVersion(\"HTTP\", 1, 1)。新增类型用法必须同时补真实 import。"
                + "Mockito stub/verify 涉及声明 IOException 的 execute/close 等 API 时，测试方法也需捕获或声明异常；"
                + "HttpClient4 的 execute 接收 HttpUriRequest；RequestBuilder 只传给业务 invoke，底层 execute 用 build 后的请求。"
                + "stub/verify 使用 any(HttpUriRequest.class)，补真实 import，不能使用 any(RequestBuilder.class)。"
                + "HttpUriRequest 未声明 getEntity；请求体使用 captured instanceof HttpEntityEnclosingRequest ? "
                + "((HttpEntityEnclosingRequest) captured).getEntity() : null，补 org.apache.http.HttpEntityEnclosingRequest。"
                + "无请求体时 build 可能返回非实体请求，必须保留该分支的 null 断言，不能盲目强转。"
                + "Apache StringEntity 没有 getValue；读取请求/响应正文用 EntityUtils.toString(真实实体, Consts.UTF_8)，"
                + "补 org.apache.http.util.EntityUtils 和 org.apache.http.Consts，不能用实体自身 toString() 读取正文。"
                + "ReflectionTestUtils.setField(HttpClientUtil.class, ...) 也会触发首次静态初始化，"
                + "必须先准备 ApplicationContext.getBean(ReadConfig.class)/getBean(BeeTraceClientHelper.class) 及有效容量配置。"
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
                + "EventData 是普通 Mock 时，单独填充 eventFields 不会绑定 getData；被测调用前必须按该用例"
                + "用 when(eventData.getData()).thenReturn(eventFields) 或真实 Answer 返回预期 Map。"
                + "成功、异常及空响应分支分别核对输入绑定，null 分支保留 null；不能放宽具体 Map/IP 的交互验证。"
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

    /** 同一计划的业务源码和测试统一使用最终预演内容，不受补丁或 Map 遍历顺序影响。 */
    public void validatePreviews(AgentContext context, Map<File, String> originals, Map<File, String> previews) {
        Map<String, String> sources = new LinkedHashMap<>();
        previews.forEach((file, source) -> sources.put(workspaceService.relativePath(file), source));
        previews.forEach((file, after) -> validatePreview(context, file, originals.get(file), after, sources));
    }

    private void validatePreview(AgentContext context, File file, String before, String after,
                                 Map<String, String> previews) {
        State state = state(context);
        String path = workspaceService.relativePath(file);
        for (Entry entry : state.entries) {
            if (entry.source.equals(path) && before != null && !before.isEmpty() && !before.equals(after)) {
                validateProductionVisibility(before, after, entry.sourceClass);
            }
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
                List<String> errors;
                try {
                    errors = validateSource(state, entry, after, previews);
                    errors.addAll(inspector.validateReportedApiUsage(after, context.getVerifyErrors()));
                    if (inspector.requiresInlineMocking(after)) {
                        errors.addAll(dependencySupport.validateMockitoCapability(context, previews));
                    }
                } catch (RuntimeException exception) {
                    // 修复补丁：解析直接抛异常也保存未落盘候选，路径放在源码前，日志仍能定位。
                    String snapshot = rejectedPreviewSnapshot(context, entry.testClass, after);
                    throw new IllegalArgumentException("S2 测试候选预检异常: candidatePath=" + path
                            + (snapshot.isEmpty() ? " snapshot=未保存（查看快照限制日志）" : " snapshot=" + snapshot)
                            + "；原因=" + exception.getMessage(), exception);
                }
                if (!errors.isEmpty()) {
                    String snapshot = rejectedPreviewSnapshot(context, entry.testClass, after);
                    throw new IllegalArgumentException("测试修改不满足门禁: path=" + path
                            + (snapshot.isEmpty() ? "" : " snapshot=" + snapshot) + " errors=" + errors);
                }
                if (!before.isEmpty()) {
                    // 断言保护仍以修改前的业务语义为基线，避免同时修改生产代码来放宽断言。
                    inspector.preserveAssertions(before, after, inspector.read(workspaceService.resolve(entry.source)),
                            entry.testClass, context.getVerifyErrors(), requestJsonProperties(state),
                            requestHeaderSource(context, state));
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

    /** 保留未执行的候选源码以定位误判；每任务最多三份，不写日志正文或目标工程。 */
    private String rejectedPreviewSnapshot(AgentContext context, String testClass, String source) {
        Object stored = context.getVariables().get("s2_rejected_preview_count");
        int count = stored instanceof Number ? ((Number) stored).intValue() : 0;
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (count >= 3 || bytes.length > 256 * 1024) {
            // 修复补丁：明确快照缺失原因，不扩大保留数量或覆盖已有诊断文件。
            log.warn("S2流程 flow={} 候选快照未保存 testClass={} count={} bytes={} reason={}",
                    context.getVariables().get("s2_flow_id"), testClass, count, bytes.length,
                    count >= 3 ? "每任务最多3份" : "候选超过256KiB");
            return "";
        }
        try {
            java.nio.file.Path directory = new File("logs/s2-preflight").toPath().toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(directory);
            String flow = String.valueOf(context.getVariables().get("s2_flow_id")).replaceAll("[^A-Za-z0-9_-]", "_");
            java.nio.file.Path snapshot = java.nio.file.Files.createTempFile(directory,
                    flow + "-" + testClass.replaceAll("[^A-Za-z0-9_-]", "_") + "-", ".java.txt");
            java.nio.file.Files.write(snapshot, bytes);
            context.getVariables().put("s2_rejected_preview_count", count + 1);
            log.warn("S2流程 flow={} 预检拒绝的候选源码未执行，诊断文件={}", flow, snapshot);
            return snapshot.toString();
        } catch (java.io.IOException | SecurityException exception) {
            log.warn("S2流程 flow={} 保存预检候选源码失败 type={}",
                    context.getVariables().get("s2_flow_id"), exception.getClass().getSimpleName());
            return "";
        }
    }

    /** 修复补丁：指明实际解析失败文件，区分候选测试与关联业务源码。 */
    private CompilationUnit parseSource(String source, String path) {
        try {
            return inspector.parse(source);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("解析文件=" + path + "；" + exception.getMessage(), exception);
        }
    }

    /** 测试跨包访问失败须修测试入口，不能通过改变生产方法可见性迁就测试。 */
    private void validateProductionVisibility(String before, String after, String sourceClass) {
        CompilationUnit original = inspector.parse(before);
        CompilationUnit candidate = inspector.parse(after);
        original.getTypes().stream().filter(type -> type.getNameAsString().equals(sourceClass)).forEach(type ->
                candidate.getTypes().stream().filter(next -> next.getNameAsString().equals(sourceClass)).forEach(next ->
                        type.getMethods().forEach(method -> next.getMethods().stream()
                                .filter(updated -> updated.getSignature().asString().equals(method.getSignature().asString()))
                                .filter(updated -> updated.getAccessSpecifier() != method.getAccessSpecifier())
                                .findFirst().ifPresent(updated -> {
                                    throw new IllegalArgumentException("S2 修复不得改变生产方法可见性: " + sourceClass + "."
                                            + method.getSignature().asString() + "；protected 跨包测试使用测试侧子类同签名公开桥接并直接 super 转发，"
                                            + "测试变量改用该子类，保留断言内原调用、参数及预期，不将生产 protected 改 public");
                                }))));
    }

    private List<String> validateSource(State state, Entry entry, String source) {
        return validateSource(state, entry, source, Collections.emptyMap());
    }

    private List<String> validateSource(State state, Entry entry, String source, Map<String, String> previews) {
        CompilationUnit unit = parseSource(source, entry.test);
        Set<String> references = unit.findAll(com.github.javaparser.ast.expr.ClassExpr.class).stream()
                .map(literal -> literal.getType().asString()).collect(java.util.stream.Collectors.toSet());
        unit.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class)
                .forEach(field -> references.add(field.getScope().toString()));
        unit.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)
                .forEach(call -> call.getScope().ifPresent(scope -> references.add(scope.toString())));
        collectTypeReferences(unit, references);
        String business = previewSource(entry.source, previews);
        collectTypeReferences(parseSource(business, entry.source), references);
        Map<String, String> relatedSources = new LinkedHashMap<>();
        // 本次修改类及已成功读取的复用支持类均可提供真实 API，不扫描目标工程。
        for (int depth = 0; depth < state.referenceSources.size(); depth++) {
            int previousSize = relatedSources.size();
            for (Map.Entry<String, String> related : state.referenceSources.entrySet()) {
                if (!related.getValue().equals(entry.source) && !relatedSources.containsKey(related.getKey())
                        && references.stream().anyMatch(name ->
                        name.equals(related.getKey()) || name.endsWith("." + related.getKey()))) {
                    String content = previewSource(related.getValue(), previews);
                    relatedSources.put(related.getKey(), content);
                    collectTypeReferences(parseSource(content, related.getValue()), references);
                }
            }
            if (previousSize == relatedSources.size()) {
                break;
            }
        }
        return inspector.validate(source, entry.sourceClass, entry.testClass, business, relatedSources);
    }

    private void collectTypeReferences(CompilationUnit unit, Set<String> references) {
        unit.getImports().stream().filter(declaration -> !declaration.isStatic() && !declaration.isAsterisk())
                .forEach(declaration -> references.add(declaration.getNameAsString()));
        unit.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class)
                .forEach(type -> references.add(type.getNameAsString()));
    }

    private String responseCoverageRules(AgentContext context) {
        State state = state(context);
        Entry active = current(state);
        String errors = String.join("\n", context.getVerifyErrors());
        boolean handler = false;
        boolean entry = false;
        for (Entry candidate : state.entries) {
            if (candidate != active && !(context.getPhase() == AgentPhase.REPAIR
                    && (errors.contains(candidate.testClass) || errors.contains(candidate.test)))) {
                continue;
            }
            CompilationUnit business = inspector.parse(inspector.read(workspaceService.resolve(candidate.source)));
            handler |= business.findAll(MethodDeclaration.class).stream().anyMatch(method ->
                    method.getNameAsString().equals("doPost") && method.findAll(MethodCallExpr.class).stream()
                            .anyMatch(call -> call.getNameAsString().equals("parse")));
            entry |= business.findAll(MethodDeclaration.class).stream().anyMatch(method ->
                    method.getNameAsString().equals("ahiInvoke") && method.findAll(MethodCallExpr.class).stream()
                            .anyMatch(call -> call.getNameAsString().equals("getAsBigDecimal")));
        }
        if (!handler && !entry) {
            return "";
        }
        return (handler ? "模板 Handler 专项：非法 JSON 用 not-json、缺响应头用 {}，均通过真实 HTTP 响应路径验证返回 null。" : "")
                + (entry ? "新版入口专项：按 BigDecimal 契约补 0.1/0.123456，验证可用性1和 new BigDecimal(对应十进制字符串)，"
                + "不经过 float/double；另补成功码0000下缺 response_data、缺评分、缺 time、评分为 bad-score、time为 bad-time "
                + "五个独立异常用例，改变一个字段时另一个字段必须有效。全部验证 ahiResultAvailable=0、time=0、"
                + "rst_code=0000、评分BigDecimal.valueOf(2)，不要只验证不抛异常。" : "")
                + "每例用完整响应 JSON 传响应夹具（方法名不限，可同时接收状态码，必须将正文放入实际返回响应的 StringEntity）"
                + "或 new StringEntity(完整响应 JSON, Consts.UTF_8)，接入当前 HTTP execute 桩，调用真实业务；"
                + "正文、实体及响应允许经本 @Test 内唯一声明且无重赋值的局部变量传递；execute 返回桩可在同类 BeforeEach/Before 中。"
                + "Handler 可 assertNull(handler.doPost(...)) 或先保存返回值再 assertNull，支持 JUnit 消息参数。"
                + "不是声明未使用的 JSON。保留原用例及所有有效断言。新精度契约由更新后的 S1 入口模板生成。\n";
    }

    private String previewSource(String path, Map<String, String> previews) {
        return previews.containsKey(path) ? previews.get(path) : inspector.read(workspaceService.resolve(path));
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
        if (businessContracts.supports(context)) {
            addRelatedSources(state, paths);
        }
        addInitializationSources(state, paths);
        for (File pom : dependencySupport.pomFiles(context)) {
            paths.add(workspaceService.relativePath(pom));
        }
        return paths;
    }

    /** 从本任务已知源码沿真实类型引用补齐上下文，复用类不额外变成测试条目。 */
    private void addRelatedSources(State state, List<String> paths) {
        Set<String> references = new LinkedHashSet<>();
        Set<String> scanned = new LinkedHashSet<>();
        for (int depth = 0; depth <= state.referenceSources.size(); depth++) {
            for (String path : new ArrayList<>(paths)) {
                if (path.endsWith(".java") && workspaceService.resolve(path).isFile() && scanned.add(path)) {
                    collectTypeReferences(inspector.parse(inspector.read(workspaceService.resolve(path))), references);
                }
            }
            int previousSize = paths.size();
            state.referenceSources.forEach((name, path) -> {
                if (!paths.contains(path) && references.stream().anyMatch(reference -> reference.equals(name)
                        || reference.endsWith("." + name))) {
                    paths.add(path);
                }
            });
            if (previousSize == paths.size()) {
                break;
            }
        }
    }

    /** 固定 HTTP 创建链及其三个请求实体，不扫描工程或读取其它模板。 */
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
            for (String name : Arrays.asList("ReadConfig", "HttpClientUtil", "AhiHttpInvokerHandler",
                    "AhiHttpBodyEntity", "AhiHttpBodyHeaderEntity", "AhiHttpBodyDataEntity")) {
                for (Entry entry : state.entries) {
                    if (name.equals(entry.sourceClass) && !paths.contains(entry.source)) {
                        paths.add(entry.source);
                        break;
                    }
                }
            }
        }
    }

    /** 使用修改前磁盘上的本任务 Handler，不能用同计划生产补丁为错误断言制造依据。 */
    private String requestHeaderSource(AgentContext context, State state) {
        if (context.getVerifyErrors().stream().noneMatch(error -> error.contains("application/json; charset=UTF-8"))) {
            return "";
        }
        for (Entry entry : state.entries) {
            if (entry.sourceClass.equals("AhiHttpInvokerHandler")) {
                return inspector.read(workspaceService.resolve(entry.source));
            }
        }
        return "";
    }

    /** 只采用本任务三个请求实体的真实 Jackson 注解，存在歧义的名称不参与等价修复。 */
    private Map<String, String> requestJsonProperties(State state) {
        Map<String, String> properties = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        for (Entry entry : state.entries) {
            if (!Arrays.asList("AhiHttpBodyEntity", "AhiHttpBodyHeaderEntity", "AhiHttpBodyDataEntity")
                    .contains(entry.sourceClass)) {
                continue;
            }
            CompilationUnit unit = inspector.parse(inspector.read(workspaceService.resolve(entry.source)));
            unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                    .filter(field -> !field.isStatic()).forEach(field -> field.getAnnotationByName("JsonProperty")
                    .ifPresent(annotation -> {
                        String value = "";
                        if (annotation.isSingleMemberAnnotationExpr()
                                && annotation.asSingleMemberAnnotationExpr().getMemberValue().isStringLiteralExpr()) {
                            value = annotation.asSingleMemberAnnotationExpr().getMemberValue().asStringLiteralExpr().asString();
                        } else if (annotation.isNormalAnnotationExpr()) {
                            value = annotation.asNormalAnnotationExpr().getPairs().stream()
                                    .filter(pair -> "value".equals(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr())
                                    .map(pair -> pair.getValue().asStringLiteralExpr().asString()).findFirst().orElse("");
                        }
                        if (!value.isEmpty()) {
                            String key = value.replace("_", "").toLowerCase(java.util.Locale.ROOT);
                            String previous = properties.putIfAbsent(key, value);
                            if (previous != null && !previous.equals(value)) {
                                ambiguous.add(key);
                            }
                        }
                    }));
        }
        ambiguous.forEach(properties::remove);
        return properties;
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
        private final Map<String, String> referenceSources = new LinkedHashMap<>();
    }

    private static class Entry {
        private String source;
        private String sourceClass;
        private String test;
        private String testClass;
        private boolean complete;
    }
}
