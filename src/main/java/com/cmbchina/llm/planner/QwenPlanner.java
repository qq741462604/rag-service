package com.cmbchina.llm.planner;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import com.cmbchina.llm.agent.CodingTrack;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.llm.LlmClient;
import com.cmbchina.llm.project.ProjectContextService;
import com.cmbchina.llm.project.ProjectFile;
import com.cmbchina.llm.project.ProjectIndex;
import com.cmbchina.llm.prompt.PromptLoader;
import com.cmbchina.llm.prompt.PromptTemplateService;
import com.cmbchina.llm.skill.Skill;
import com.cmbchina.llm.skill.SkillManager;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.PatchFileTool;
import com.cmbchina.llm.tool.SearchMatch;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;


@Slf4j
@Component
@RequiredArgsConstructor
public class QwenPlanner implements Planner {

    @Value("${codex.agent.max-model-calls:40}")
    private int maxModelCalls;

    @Value("${codex.agent.test-generation-enabled:false}")
    private boolean testGenerationEnabled;

    private final PlanValidator planValidator;
    private final PromptLoader promptLoader;
    private final PromptTemplateService templateService;
    private final SkillManager skillManager;
    private final ProjectContextService projectContextService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final SkillTemplateCoordinator skillTemplateCoordinator;
    private final PlanResponseParser planResponseParser;
    private final PatchFileTool patchFileTool;

    @Override
    public CodePlan createPlan(AgentContext context) {
        long start = System.currentTimeMillis();
        log.info("planner start phase={} task={}", context.getPhase(), summarize(context.getTask(), 120));

        // 目标定位、当前模板读取均由系统安排，不为确定性的读取额外调用模型。
        if (context.getPhase() == AgentPhase.ANALYSIS || context.getPhase() == AgentPhase.CODING) {
            if (!hasSuccessfulReadOfTarget(context.getObservations(), context)) {
                String target = extractRealTargetPath(context.getObservations(), context);
                if (target != null) {
                    return CodePlan.builder().task(context.getTask()).steps(singleReadStep(target)).build();
                }
            }
        }
        if (context.getPhase() == AgentPhase.CODING) {
            CodePlan templateRead = skillTemplateCoordinator.ensureTemplatesRead(context, null);
            if (templateRead != null) {
                return templateRead;
            }
            CodePlan artifactRead = skillTemplateCoordinator.ensureCurrentArtifactsRead(context);
            if (artifactRead != null) {
                return artifactRead;
            }
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("PROJECT", buildProjectSummary());
        vars.put("SKILLS", skillManager.buildContext(context.getTask()));
        vars.put("TASK", context.getTask());
        vars.put("MEMORIES", "");
        vars.put("TARGET_RULE", buildTargetRule(context));
        vars.put("PHASE", context.getPhase().name());
        vars.put("OBSERVATIONS", buildObservations(context));
        vars.put("LAST_ACTION", buildLastAction(context));
        vars.put("VERIFY_ERRORS", buildVerifyErrors(context));
        vars.put("PROJECT_INDEX", buildProjectIndex(context));
        vars.put("PHASE_RULES", buildPhaseRules(context));
        CodePlan plan = generatePlan(context, vars);

        if (plan != null && plan.getSteps() != null) {
            for (PlanStep step : plan.getSteps()) {
                skillTemplateCoordinator.validateStep(context, step);
            }
        }
        plan = repairExistingFileWritePlan(context, plan);

        // 不吞掉校验失败，更不能把被拒绝的写入计划继续交给工具执行。
        planValidator.validate(plan, context);
        validatePatchSequence(plan);
        if (plan != null && plan.getSteps() != null) {
            for (PlanStep step : plan.getSteps()) {
                skillTemplateCoordinator.validateStep(context, step);
            }
        }

        log.info("planner finish cost={}ms plan={}", System.currentTimeMillis() - start, summarizePlan(plan));
        return plan;
    }

    /**
     * 基于给定变量渲染模板并调用 LLM 生成计划。
     *
     * @param context 上下文
     * @param vars    模板变量（已含 PHASE_RULES）
     * @return 解析后的计划
     */
    private CodePlan generatePlan(AgentContext context, Map<String, String> vars) {
        String template = promptLoader.load("planner");
        String prompt = templateService.render(template, vars);

        log.info(
                "========== PROMPT TAIL ==========\n{}",
                prompt.substring(
                        Math.max(0, prompt.length() - 2000)
                )
        );
        if (context.getModelCalls() >= maxModelCalls) {
            throw new IllegalStateException("规划模型调用已达到任务预算 " + maxModelCalls);
        }
        context.setModelCalls(context.getModelCalls() + 1);
        log.info("planner model call agentId={} call={}/{}", context.getAgentId(), context.getModelCalls(), maxModelCalls);
        String response = llmClient.chat(prompt);
        log.info(
                "========== PLAN RESPONSE ==========\n{}",
                response
        );
        return parse(context.getTask(), response);
    }

    private CodePlan repairExistingFileWritePlan(AgentContext context, CodePlan plan) {
        if (plan == null || plan.getSteps() == null) {
            return plan;
        }
        for (PlanStep step : plan.getSteps()) {
            if (isCreateTool(step.getTool()) && step.getInput() instanceof ToolInput) {
                ToolInput input = (ToolInput) step.getInput();
                if (isExistingFile(input.getPath())) {
                    File file = workspaceService.resolve(input.getPath());
                    String path = workspaceService.relativePath(file);
                    if (hasSuccessfulReadOfFile(path, context)) {
                        throw new IllegalArgumentException("文件已读取，必须根据 Observations 中原内容使用 patch_file 局部修改，"
                                + "不能再次 write_file/create_file，也不能用整文件替换局部片段: " + path);
                    }
                    log.info("existing file write redirected to read_file path={} phase={}", path, context.getPhase());
                    context.getVerifyErrors().add("已有文件不是新文件，原计划未执行。读取 " + path
                            + " 后必须保留无关配置/代码，使用 patch_file 局部补充当前模板要求，禁止覆盖。");
                    return CodePlan.builder().task(context.getTask()).steps(singleReadStep(path)).build();
                }
            }
        }
        return plan;
    }

    private boolean hasSuccessfulReadOfFile(String path, AgentContext context) {
        for (Observation observation : context.getObservations()) {
            if (observation.isSuccess() && "read_file".equals(observation.getTool())
                    && observation.getResult() instanceof FileContent
                    && sameWorkspaceFile(path, ((FileContent) observation.getResult()).getPath())) {
                return true;
            }
        }
        return false;
    }

    private String stripReadLineNumbers(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        List<String> content = new ArrayList<>();
        for (String line : lines) {
            content.add(line.replaceFirst("^\\d+: ?", ""));
        }
        return String.join("\n", content);
    }

    /**
     * 每个文件只读一次，在内存中按执行顺序应用补丁，避免后续步骤被旧磁盘内容误拒绝。
     * 任一步失败均不落盘，返回具体步骤和真实片段供下一次规划纠正。
     */
    private void validatePatchSequence(CodePlan plan) {
        if (plan == null || plan.getSteps() == null) {
            return;
        }
        Map<File, String> previews = new HashMap<>();
        int index = 0;
        for (PlanStep step : plan.getSteps()) {
            index++;
            if (!"patch_file".equals(step.getTool()) || !(step.getInput() instanceof ToolInput)) {
                continue;
            }
            ToolInput input = (ToolInput) step.getInput();
            String content = null;
            try {
                File file = workspaceService.resolveExistingFile(input.getPath()).getCanonicalFile();
                content = previews.get(file);
                if (content == null) {
                    content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                }
                if (!content.isEmpty() && isInvalidPatchOldText(input.getOldText())) {
                    throw new IllegalArgumentException("oldText 为空或占位符");
                }
                previews.put(file, patchFileTool.previewPatch(content, input));
            } catch (Exception e) {
                String message = "第 " + index + " 步 patch_file 预检失败，原计划全部未执行。path="
                        + input.getPath() + "；原因=" + e.getMessage()
                        + "\n失败的 oldText:\n" + summarize(input.getOldText(), 700)
                        + "\n文件在本步骤之前的实际内容（含前序补丁预演，未落盘）:\n"
                        + patchSourceExcerpt(content, input.getOldText())
                        + "\n请从真实内容复制唯一局部片段，保留原注释，不要重排未修改的上下文。"
                        + "前序步骤未落盘，纠正后需重新提交完整计划；禁止降级整文件覆盖。";
                log.warn("patch preflight rejected step={} path={} error={}", index, input.getPath(), e.getMessage());
                throw new IllegalArgumentException(message, e);
            }
        }
    }

    private String patchSourceExcerpt(String content, String oldText) {
        if (content == null) {
            return "无法读取，请先 read_file 确认真实路径。";
        }
        if (content.length() <= 2400) {
            return content;
        }
        int start = 0;
        if (oldText != null) {
            for (String line : oldText.split("\\R")) {
                if (line.trim().length() >= 8) {
                    int match = content.indexOf(line.trim());
                    if (match >= 0) {
                        start = Math.max(0, match - 300);
                        break;
                    }
                }
            }
        }
        return content.substring(start, Math.min(content.length(), start + 2400))
                + "\n...（仅展示相关片段，需要更多上下文时按范围 read_file）";
    }

    private boolean isCreateTool(String tool) {
        return "write_file".equals(tool) || "create_file".equals(tool);
    }

    private boolean isExistingFile(String path) {
        if (path == null) {
            return false;
        }
        try {
            File file = workspaceService.resolve(path);
            return file.exists() && file.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 AgentContext 的 Skill 列表中提取目标类名。
     *
     * <p>Skill 的 Target 配置中指定了 className（如 {@code DataPrepEventHandler}），
     * 该方法遍历所有匹配的 Skill，返回第一个非空的目标类名。</p >
     *
     * @param context Agent 上下文
     * @return Skill 指定的目标类名，未配置时返回 null
     */
    private String getSkillTargetClassName(AgentContext context) {
        if (context == null || context.getSkills() == null || context.getSkills().isEmpty()) {
            return null;
        }
        for (Skill skill : context.getSkills()) {
            if (skill.getTarget() != null
                    && skill.getTarget().getClassName() != null
                    && !skill.getTarget().getClassName().trim().isEmpty()) {
                return skill.getTarget().getClassName().trim();
            }
        }
        return null;
    }

    /**
     * 判断文件名是否匹配 Skill 指定的目标类名。
     *
     * <p>将文件名去掉 .java 后缀后与目标类名做不区分大小写的比较。
     * 若 Skill 未指定目标类名，则返回 true（不做限制）。</p >
     *
     * @param fileName        文件名（如 {@code DataPrepEventHandler.java}）
     * @param targetClassName Skill 目标类名（如 {@code DataPrepEventHandler}）
     * @return 若匹配或未指定目标类名则返回 true
     */
    private boolean matchesSkillTarget(String fileName, String targetClassName) {
        if (targetClassName == null || targetClassName.isEmpty()) {
            return true;
        }
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String name = fileName;
        if (name.endsWith(".java")) {
            name = name.substring(0, name.length() - 5);
        }
        return name.equalsIgnoreCase(targetClassName);
    }

    /**
     * 从观察结果 / PROJECT_INDEX 中提取真实的 .java 目标文件路径。
     *
     * <p>当 LLM 反复读取一个编造的不存在路径时，从成功观察中提取真实存在的
     * Java 源文件绝对/相对路径，用于注入强制重写规则，纠正 LLM 的路径认知。
     * 提取优先级：</p >
     * <ol>
     *     <li>最近观察中 read_file 成功返回的 .java 目标文件路径（FileContent.path）；</li>
     *     <li>最近观察中 search_code 成功返回的 .java 文件路径（SearchMatch.path）；</li>
     *     <li>PROJECT_INDEX 中的首个 .java 源码文件路径（ProjectIndex.files）。</li>
     * </ol>
     *
     * @param observations 最近观察结果
     * @param context      上下文（用于读取 PROJECT_INDEX）
     * @return 真实目标 Java 文件路径，未找到时返回 null
     */
    private String extractRealTargetPath(List<Observation> observations, AgentContext context) {
        String targetClassName = getSkillTargetClassName(context);

        // 1) 最高优先级：从 search_code 成功结果中提取匹配 Skill 目标类名的 .java 路径
        //    ANALYSIS 阶段 search_code 定位到的路径是最可靠的，优先使用
        if (observations != null) {
            for (Observation o : observations) {
                if ("search_code".equals(o.getTool())
                        && o.isSuccess()
                        && o.getResult() instanceof List) {
                    for (Object item : (List<?>) o.getResult()) {
                        if (item instanceof SearchMatch) {
                            String path = ((SearchMatch) item).getPath();
                            if (isRealJavaSource(path)
                                    && matchesSkillTarget(new File(path).getName(), targetClassName)) {
                                return path;
                            }
                        }
                    }
                }
            }
        }

        // 2) 从 read_file 成功结果中提取匹配 Skill 目标类名的 .java 路径
        if (observations != null) {
            for (Observation o : observations) {
                if ("read_file".equals(o.getTool())
                        && o.isSuccess()
                        && o.getResult() instanceof FileContent) {
                    FileContent content = (FileContent) o.getResult();
                    String path = content.getPath();
                    if (isRealJavaSource(path)
                            && matchesSkillTarget(new File(path).getName(), targetClassName)) {
                        return path;
                    }
                }
            }
        }

        // Skill 指定入口时不能退化为任意 Java 类，否则会持续读取错误入口。
        if (targetClassName != null) {
            ProjectIndex index = context.getProjectIndex();
            if (index != null && index.getFiles() != null) {
                for (ProjectFile file : index.getFiles()) {
                    if (isRealJavaSource(file.getPath())
                            && matchesSkillTarget(new File(file.getPath()).getName(), targetClassName)) {
                        return file.getPath();
                    }
                }
            }
            return null;
        }

        // 3) 没有指定入口类时，允许从已确认的 Java 源码中选择。
        if (observations != null) {
            for (Observation o : observations) {
                if ("search_code".equals(o.getTool())
                        && o.isSuccess()
                        && o.getResult() instanceof List) {
                    for (Object item : (List<?>) o.getResult()) {
                        if (item instanceof SearchMatch) {
                            String path = ((SearchMatch) item).getPath();
                            if (isRealJavaSource(path)) {
                                return path;
                            }
                        }
                    }
                }
            }
        }

        // 4) 从 read_file 成功结果中提取任意真实 .java 路径
        if (observations != null) {
            for (Observation o : observations) {
                if ("read_file".equals(o.getTool())
                        && o.isSuccess()
                        && o.getResult() instanceof FileContent) {
                    FileContent content = (FileContent) o.getResult();
                    String path = content.getPath();
                    if (isRealJavaSource(path)) {
                        return path;
                    }
                }
            }
        }

        // 5) 兜底：从 PROJECT_INDEX 中提取匹配 Skill 目标类名的 .java 源码文件
        ProjectIndex index = context.getProjectIndex();
        if (index != null && index.getFiles() != null) {
            for (ProjectFile file : index.getFiles()) {
                String path = file.getPath();
                if (isRealJavaSource(path)
                        && matchesSkillTarget(new File(path).getName(), targetClassName)) {
                    return path;
                }
            }
        }

        // 6) 最终兜底：从 PROJECT_INDEX 中提取第一个 .java 源码文件
        if (index != null && index.getFiles() != null) {
            for (ProjectFile file : index.getFiles()) {
                String path = file.getPath();
                if (isRealJavaSource(path)) {
                    return path;
                }
            }
        }
        return null;
    }

    /**
     * 判断给定路径是否为一个真实存在的 Java 源码文件（.java）路径。
     *
     * <p>仅接受以 {@code .java} 结尾的路径，排除 .md 模板、pom.xml、配置文件等，
     * 并通过 workspaceService 校验目标文件真实存在，避免把不存在的编造路径当作真实路径注入。</p >
     *
     * @param path 待判断路径
     * @return 若为真实存在的 .java 文件路径则返回 true
     */
    private boolean isRealJavaSource(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        String normalized = path.trim().replace('\\', '/');
        if (!normalized.endsWith(".java")) {
            return false;
        }
        try {
            File file = workspaceService.resolve(path.trim());
            return file.exists() && file.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    private List<PlanStep> singleReadStep(
            String path
    ) {


        List<PlanStep> steps =
                new ArrayList<>();

        steps.add(
                PlanStep.builder()
                        .order(1)
                        .tool("read_file")
                        .input(
                                ToolInput.builder()
                                        .path(path)
                                        .startLine(1)
                                        .endLine(1000)
                                        .build()
                        )
                        .description("Read target file before patch_file")
                        .build()
        );

        return steps;

    }

    private boolean isInvalidPatchOldText(
            String oldText
    ) {

        if (oldText == null
                || oldText.trim().isEmpty()) {

            return true;

        }

        String normalized =
                oldText.trim();

        // 放宽检查条件，允许更多格式的文本
        // 只有当文本明显是占位符时才认为是无效的
        return normalized.equals("{{")
                || normalized.equals("}}")
                || normalized.equals("READ_FILE_CONTENT")
                || normalized.equals("...")
                || normalized.matches("^\\{\\{.*\\}\\}$")  // 完整的{{...}}模式
                || normalized.equals("");

    }

    private CodePlan parse(String task, String json) {
        return planResponseParser.parse(task, json);
    }


    private String buildLastAction(AgentContext context) {
        if (context.getObservations() == null || context.getObservations().isEmpty()) {
            return "当前没有执行任何工具";
        }

        Observation last = context.getObservations().get(context.getObservations().size() - 1);

        StringBuilder sb = new StringBuilder();
        sb.append("最近一次执行:\n");
        sb.append("tool=").append(last.getTool()).append("\n");
        sb.append("success=").append(last.isSuccess()).append("\n");

        if (!last.isSuccess()) {
            sb.append("\n工具执行失败:\n");
            sb.append(last.getError());
            sb.append("\n必须修正Plan，不允许重复错误操作。\n");
            return sb.toString();
        }

        return sb.toString();
    }

    private String buildPhaseRules(AgentContext context) {
        switch (context.getPhase()) {
            case ANALYSIS:
                return "当前阶段 ANALYSIS\n" +
                        "允许工具:\n" +
                        "- list_files\n" +
                        "- search_code\n" +
                        "- read_file\n" +
                        "目标:分析项目，定位Skill指定的目标类，不修改代码。\n\n" +
                        "分步执行规则（强制）:\n" +
                        "1. 每次只能生成一个工具调用步骤，禁止把多个工具放在同一个计划中。\n" +
                        "2. 必须先 search_code 定位目标类，拿到返回的真实路径。\n" +
                        "3. 拿到 search_code 返回的路径后，在下一轮再 read_file 读取目标类。\n" +
                        "4. read_file 的 path 必须是真实确定的路径，禁止留空或写占位符。\n" +
                        "5. 禁止把依赖上一步结果的步骤提前放进同一个计划。";

            case CODING:
                return buildCodingPhaseRules(context);

            case VERIFY:
                return "当前阶段 VERIFY\n" +
                        "禁止修改代码\n" +
                        "只能:\n" +
                        "- read_file\n" +
                        "- search_code\n";

            case REPAIR:
                return "当前阶段 REPAIR：只修复 VERIFY_ERRORS 指明的静态检查问题。\n"
                        + "S2 暂停期间不生成测试类、不执行编译或测试；系统最多允许两轮静态修正。\n"
                        + "已有文件先 read_file 再 patch_file；新文件确认不存在后 write_file。\n"
                        + "禁止通过 write_file 覆盖已有文件，禁止修改参考模板或无关业务。\n";

            case FINISH:
                return "任务已经完成，不生成任何tool";

            default:
                return "";
        }
    }

    /**
     * 构建 CODING 阶段的动态规则。
     *
     * <p>核心目的：解决 LLM 在 CODING 阶段反复只生成 read_file、不生成 patch_file 的问题。
     * 规则会根据 OBSERVATIONS（最近观察结果）中是否已有目标文件 / pom.xml 的 read_file
     * 成功结果，动态追加引导，强制推进到写入阶段，避免无限 read 循环。</p >
     *
     * @param context 上下文
     * @return CODING 阶段规则文本
     */
    private String buildCodingPhaseRules(AgentContext context) {
        if (context.getCodingTrack() == CodingTrack.S2_TEST && testGenerationEnabled) {
            return "当前 CODING S2：仅生成或修改 src/test 下的测试类。先读取真实业务签名，再生成有断言的测试。"
                    + "已有文件必须 read_file 后 patch_file；禁止覆盖或改写业务代码。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前阶段 CODING（S1 业务代码）。S2 测试生成和自动测试已暂停。\n");
        sb.append("本轮只处理当前模板产物，按模板固定结构、协议字段和调用链生成。\n");
        sb.append("新文件必须确认不存在后 write_file；已有文件必须先 read_file，再 patch_file。\n");
        sb.append("patch_file 的 oldText 是已读真实片段，newText 是该片段的替换内容，不能用整文件替换局部片段。\n");
        sb.append("允许读取当前模板对应的已有类、配置、pom 和依赖以补齐真实信息。\n");
        sb.append("目标入口在最后一份入口模板才修改，禁止先引用尚未实现的依赖类。\n");
        sb.append(skillTemplateCoordinator.buildCurrentRules(context));
        return sb.toString();
    }

    private boolean hasSuccessfulReadOfTarget(List<Observation> observations, AgentContext context) {
        if (observations == null || observations.isEmpty()) {
            return false;
        }
        String targetClassName = getSkillTargetClassName(context);
        for (Observation o : observations) {
            if ("read_file".equals(o.getTool())
                    && o.isSuccess()
                    && isTargetRead(o, targetClassName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断某条观察是否为目标文件的 read 结果。
     *
     * <p>排除以下非目标文件的读取：</p >
     * <ul>
     *     <li>pom.xml / pom.xsd（依赖配置文件）</li>
     *     <li>.md 文件（Skill 参考模板，如 docs/predict-call-template.md）</li>
     *     <li>.yml / .yaml / .properties 文件（配置文件，如 application.yml）</li>
     * </ul>
     * <p>只有读取了真正的目标 Java 源码文件（.java），才视为"目标文件已读取"，
     * 避免把参考模板或配置文件的读取误判为目标文件已读取，导致强制重写机制错误触发。</p >
     *
     * <p>增强：当 Skill 指定了目标类名时，还会检查读取的文件名是否匹配目标类名，
     * 避免把 LLM 读取的"存在但错误的 .java 文件"（如 {@code CashAdvanceDecisionFlowExtension.java}）
     * 误判为目标文件已读取。例如 Skill 指定目标类为 {@code DataPrepEventHandler}，
     * 但 LLM 读取了 {@code CashAdvanceDecisionFlowExtension.java}（真实存在但错误的文件），
     * 此时不应视为目标文件已读取。</p >
     *
     * @param o               观察
     * @param targetClassName Skill 指定的目标类名（为 null 时不做类名匹配限制）
     * @return 是否为目标文件读取
     */
    private boolean isTargetRead(Observation o, String targetClassName) {
        if (!(o.getResult() instanceof FileContent)) {
            return false;
        }
        FileContent content = (FileContent) o.getResult();
        String path = content.getPath();
        if (path == null) {
            return false;
        }
        String normalized = path.replace('\\', '/').toLowerCase();
        // 排除依赖配置文件
        if (normalized.endsWith("pom.xml") || normalized.endsWith("pom.xsd")) {
            return false;
        }
        // 排除 Skill 参考模板文件（.md）
        if (normalized.endsWith(".md")) {
            return false;
        }
        // 排除配置文件（.yml / .yaml / .properties）
        if (normalized.endsWith(".yml")
                || normalized.endsWith(".yaml")
                || normalized.endsWith(".properties")) {
            return false;
        }
        // 非 .java 文件不视为目标文件
        if (!normalized.endsWith(".java")) {
            return false;
        }
        // 当 Skill 指定了目标类名时，检查文件名是否匹配目标类名
        // 避免 LLM 读取"存在但错误的 .java 文件"被误判为目标文件已读取
        if (!matchesSkillTarget(new File(path).getName(), targetClassName)) {
            return false;
        }
        // 文件名匹配 Skill 目标类名，视为目标文件已读取
        return true;
    }

    private String buildObservations(AgentContext context) {
        List<Observation> list = getPlannerObservations(context);

        if (list == null || list.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        int start = 0;

        for (int i = start; i < list.size(); i++) {
            Observation o = list.get(i);

            sb.append("tool=").append(o.getTool()).append("\n");
            sb.append("success=").append(o.isSuccess()).append("\n");

            if (o.getInput() != null) {
                String input = String.valueOf(o.getInput());
                if (input.length() > 300) {
                    input = input.substring(0, 300);
                }
                sb.append("input=").append(input).append("\n");
            }
            if (o.getResult() != null) {
                String result = formatObservationResult(o.getResult());
                // 仅保留总览、当前模板和最近源码，分别限长；截断时明确提示补读。
                int maxResultLength;
                if ("read_file".equals(o.getTool())) {
                    maxResultLength = 20000;
                } else {
                    maxResultLength = 500;
                }
                if (result.length() > maxResultLength) {
                    result = result.substring(0, maxResultLength)
                            + "\n... (file content truncated, total length=" + result.length() + ")";
                }
                sb.append("result=").append(result).append("\n");
            }
            sb.append("---\n");
        }

        return sb.toString();
    }

    private String formatObservationResult(Object result) {
        if (result instanceof FileContent) {
            FileContent content = (FileContent) result;
            return "path="
                    + content.getPath()
                    + "\ncontent:\n"
                    + stripReadLineNumbers(content.getLines());
        }
        return String.valueOf(result);
    }

    private String extractObservationPath(Observation observation) {
        if (observation == null || observation.getInput() == null) {
            return null;
        }
        if (observation.getInput() instanceof ToolInput) {
            return ((ToolInput) observation.getInput()).getPath();
        }
        if (observation.getInput() instanceof Map) {
            Object path = ((Map<?, ?>) observation.getInput()).get("path");
            return path == null ? null : String.valueOf(path);
        }
        return String.valueOf(observation.getInput());
    }

    private String buildTargetRule(AgentContext context) {
        String target = extractRealTargetPath(context.getObservations(), context);
        StringBuilder rule = new StringBuilder("已定位入口真实路径: ").append(target).append("\n");
        for (Observation observation : context.getObservations()) {
            if (observation.isSuccess() && observation.getResult() instanceof FileContent) {
                FileContent file = (FileContent) observation.getResult();
                if (sameWorkspaceFile(target, file.getPath())) {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                            "(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(stripReadLineNumbers(file.getLines()));
                    if (matcher.find()) {
                        String pkg = matcher.group(1);
                        rule.append("入口真实 package: ").append(pkg)
                                .append("；辅助类根包: ").append(pkg.replaceAll("\\.extension$", ""))
                                .append("。禁止照抄模板示例包名。\n");
                    }
                }
            }
        }
        return rule.toString();
    }

    private boolean sameWorkspaceFile(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            return workspaceService.resolve(left).getCanonicalFile()
                    .equals(workspaceService.resolve(right).getCanonicalFile());
        } catch (Exception e) {
            return false;
        }
    }

    private String buildProjectSummary() {
        String summary = projectContextService.buildSummary();
        if (summary.length() > 1500) {
            return summary.substring(0, 1500);
        }
        return summary;
    }

    private List<Observation> getPlannerObservations(AgentContext context) {
        List<Observation> result = new ArrayList<>(skillTemplateCoordinator.templateObservations(context));
        result.addAll(skillTemplateCoordinator.currentArtifactObservations(context));
        List<Observation> history = context.getObservations();
        List<Observation> recent = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && recent.size() < 3; i--) {
            Observation observation = history.get(i);
            if (!result.contains(observation)
                    && !hasPinnedFileRead(result, observation)
                    && !skillTemplateCoordinator.isTemplatePath(extractObservationPath(observation))) {
                recent.add(0, observation);
            }
        }
        result.addAll(recent);
        return result;
    }

    private boolean hasPinnedFileRead(List<Observation> pinned, Observation observation) {
        if (!(observation.getResult() instanceof FileContent)) {
            return false;
        }
        String path = ((FileContent) observation.getResult()).getPath();
        return pinned.stream().filter(item -> item.getResult() instanceof FileContent)
                .anyMatch(item -> sameWorkspaceFile(path, ((FileContent) item.getResult()).getPath()));
    }

    private String buildVerifyErrors(AgentContext context) {
        if (context.getVerifyErrors() == null || context.getVerifyErrors().isEmpty()) {
            return "";
        }
        Set<String> uniqueErrors = new LinkedHashSet<>(context.getVerifyErrors());
        String errors = String.join("\n", uniqueErrors);
        int maxLength = 8000;
        if (errors.length() > maxLength) {
            return errors.substring(0, maxLength)
                    + "\n... (验证错误过长，已截断；请优先修复以上错误)";
        }
        return errors;
    }

    private String buildProjectIndex(AgentContext context) {
        if (context.getProjectIndex() == null) {
            return "";
        }
        try {
            String json = objectMapper.writeValueAsString(context.getProjectIndex());
            // 截断过长的项目索引，避免 prompt 总长度超过网关处理上限导致 504 超时
            int maxIndexLength = 3000;
            if (json.length() > maxIndexLength) {
                json = json.substring(0, maxIndexLength) + "\n... (project index truncated)";
            }
            return json;
        } catch (Exception e) {
            return "";
        }
    }



    private String summarizePlan(CodePlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder();
        for (PlanStep step : plan.getSteps()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(step.getTool());
            if (step.getInput() instanceof ToolInput) {
                ToolInput input = (ToolInput) step.getInput();
                sb.append("(").append(input.getPath()).append(")");
            }
        }
        return sb.toString();
    }

    private String summarize(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

}
