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
import com.cmbchina.llm.skill.SkillMatcher;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.SearchMatch;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.workspace.WorkspaceService;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@Component
@RequiredArgsConstructor
public class QwenPlanner implements Planner {

    private final PlanValidator planValidator;
    private final PromptLoader promptLoader;
    private final PromptTemplateService templateService;
    private final SkillManager skillManager;
    private final SkillMatcher skillMatcher;
    private final ProjectContextService projectContextService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;

    @Override
    public CodePlan createPlan(AgentContext context) {
        long start = System.currentTimeMillis();
        log.info("planner start phase={} task={}", context.getPhase(), summarize(context.getTask(), 120));

        Map<String, String> vars = new HashMap<>();
        vars.put("PROJECT", buildProjectSummary());

        String matchedSkills = skillManager.buildContext(context.getTask());
        log.info("========== MATCHED SKILLS ==========\n{}", matchedSkills);

        vars.put("SKILLS", matchedSkills);

        vars.put("TASK", context.getTask());
        vars.put("TARGET_RULE", "");
        vars.put("PHASE", context.getPhase().name());

        String observations = buildObservations(context);
        vars.put("OBSERVATIONS", observations);
        vars.put("LAST_ACTION", buildLastAction(context));
        vars.put("VERIFY_ERRORS", buildVerifyErrors(context));

        String projectIndex = buildProjectIndex(context);
        vars.put("PROJECT_INDEX", projectIndex);

        // 第一轮：使用正常（动态）规则生成计划。
        vars.put("PHASE_RULES", buildPhaseRules(context));
        CodePlan plan = generatePlan(context, vars);

        // 先确保 Skill 要求的参考模板已被读取（在 forced rewrite 之前执行）。
        // 这样 ensureSkillTemplateRead 插入的 read_file(模板分块) 步骤会被
        // containsUnreadSkillReferenceRead 检测到，从而豁免 forced rewrite，
        // 避免模板读取步骤被 forced rewrite 缩减丢弃。
        // 注意：该检查不依赖于验证是否失败——即使计划本身合法，只要 LLM 跳过了模板读取就应补插。
        try {
            plan = ensureSkillTemplateRead(context, plan);
        } catch (Exception e) {
            log.error("ensureSkillTemplateRead error", e);
        }

        // 方案A1：CODING 阶段已读取目标文件，但 LLM 仍只生成只读计划时，
        // 丢弃该无效计划，注入强约束指令并重新调用 LLM，强制其生成写入动作。
        // 例外：如果计划中包含 Skill 明确要求读取、但尚未读取过的参考模板（docs/ 前缀），
        // 该读取属于写代码前的必要前置步骤，不应视为"无效纯只读计划"。
        // 注意：一旦模板已读取过，再生成"只读模板 + 无写入动作"的计划就应强制重写，
        // 避免模型利用模板读取例外无限输出只读计划、陷入只读死循环。
        // 增强触发条件：
        //   1. 目标文件已成功读取但仍只读 → 强制重写
        //   2. 计划为纯只读且正在读取不存在的路径（已有读取失败观察记录）→ 强制重写
        //   3. 计划为纯只读且包含编造的 .java 路径（不在 PROJECT_INDEX 中、非 docs/ 模板）
        //      → 强制重写，无需等待读取失败观察记录，在第 1 轮 CODING 即可触发
        // 当检测到编造路径时，模板读取豁免（containsUnreadSkillReferenceRead）不应生效，
        // 因为编造路径才是核心问题，不能因为计划中附带了一个未读模板就放行整个无效只读计划。
        // 使用完整的 observations 而非截断的 getPlannerObservations（只保留最近 3 条），
        // 避免之前读过的目标文件 / 模板被截断后误判为"未读过"，导致重复读取或错误触发强制重写。
        List<Observation> fullObservations = context.getObservations();
        if (fullObservations == null) {
            fullObservations = new ArrayList<>();
        }
        boolean targetAlreadyRead = hasSuccessfulReadOfTarget(fullObservations, context);
        boolean readingNonExistentPath = isPureReadPlan(plan)
                && containsNonExistentRead(plan, fullObservations);
        boolean readingFabricatedPath = isPureReadPlan(plan)
                && containsFabricatedJavaRead(plan, context, targetAlreadyRead);
        if (context.getPhase() == AgentPhase.CODING
                && isPureReadPlan(plan)
                && (targetAlreadyRead || readingNonExistentPath || readingFabricatedPath)
                && (!containsUnreadSkillReferenceRead(plan, fullObservations)
                || readingFabricatedPath || readingNonExistentPath)) {
            log.warn("pure read plan detected (targetRead={}, readingNonExistent={}, readingFabricated={}), force rewrite plan={}",
                    targetAlreadyRead, readingNonExistentPath, readingFabricatedPath, summarizePlan(plan));
            boolean needsPathCorrection = readingNonExistentPath || readingFabricatedPath;
            vars.put("PHASE_RULES", buildForcedRewriteRules(context, needsPathCorrection));
            // 在强制重写规则中注入真实目标路径，帮助 LLM 纠正编造的 read 路径：
            // 从观察结果 / PROJECT_INDEX 中提取真实存在的 .java 目标文件路径，
            // 避免 LLM 因看不到真实路径而反复读取一个不存在的文件。
            String realTargetPath = extractRealTargetPath(fullObservations, context);
            if (realTargetPath != null) {
                vars.put("PHASE_RULES",
                        buildForcedRewriteRules(context, needsPathCorrection)
                                + "\n\n【真实目标路径已由系统定位】目标 Java 源文件的真实路径为: "
                                + realTargetPath
                                + "\n你必须对该真实文件执行 read_file -> patch_file，"
                                + "【禁止】读取上述不存在的编造路径。\n");
            }
            CodePlan forcedPlan = generatePlan(context, vars);
            if (!isPureReadPlan(forcedPlan)) {
                plan = forcedPlan;
            } else {
                log.warn("forced rewrite still returns pure read plan, construct real-target read plan");
                // forced rewrite 仍返回只读计划：不再保留原始只读计划，而是构造一个
                // "读取真实目标 .java 文件"的计划，让下一轮 LLM 基于真实内容生成写入动作，
                // 从而打破 LLM 反复读取编造路径的死循环。
                CodePlan realReadPlan = buildRealTargetReadPlan(fullObservations, context, plan);
                if (realReadPlan != null) {
                    plan = realReadPlan;
                }
            }
        }
        try {
            plan = repairExistingFileWritePlan(context, plan);
        } catch (Exception e) {
            log.error("repairExistingFileWritePlan error", e);
        }

        try {
            plan = repairInvalidPatchPlan(context, plan);
        } catch (Exception e) {
            log.error("repairInvalidPatchPlan error", e);
        }

        try {
            planValidator.validate(plan, context);
        } catch (Exception e) {
            log.warn("plan validation failed: {}", e.getMessage());
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
        String response = llmClient.chat(prompt);
        log.info(
                "========== PLAN RESPONSE ==========\n{}",
                response
        );
        return parse(context.getTask(), response);
    }

    /**
     * 判断计划是否为"纯只读"计划（不含任何写入动作）。
     *
     * @param plan 计划
     * @return 是否为纯只读计划
     */
    private boolean isPureReadPlan(CodePlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return true;
        }
        for (PlanStep step : plan.getSteps()) {
            String tool = step.getTool();
            if ("patch_file".equals(tool)
                    || "write_file".equals(tool)
                    || "create_file".equals(tool)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断计划中是否包含"Skill 明确要求读取、但尚未读取过的参考模板"（docs/ 目录下的只读参考文件）。
     *
     * <p>这类读取是写代码前的必要前置步骤，不应被视为无效的纯只读计划而被强制重写。
     * 但该豁免仅对"尚未读取过的模板"生效：若计划中的模板已经在 OBSERVATIONS 中成功读取过，
     * 再生成"只读模板 + 无写入动作"的计划就应触发强制重写，避免模型利用模板读取例外
     * 无限输出只读计划、陷入只读死循环。</p >
     *
     * <p>兼容模型输出的两种路径形式：</p >
     * <ul>
     *     <li>相对路径：docs/predict-call-template.md</li>
     *     <li>绝对路径：D:\FlinkTest\demo\...\docs\predict-call-template.md</li>
     * </ul>
     *
     * @param plan         计划
     * @param observations 最近观察结果（用于判断模板是否已读取）
     * @return 若计划中存在读取 docs/ 目录参考文件、且该文件尚未读取过的 read_file 步骤则返回 true
     */
    private boolean containsUnreadSkillReferenceRead(CodePlan plan, List<Observation> observations) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return false;
        }
        for (PlanStep step : plan.getSteps()) {
            if (!"read_file".equals(step.getTool())
                    || !(step.getInput() instanceof ToolInput)) {
                continue;
            }
            ToolInput input = (ToolInput) step.getInput();
            String path = input.getPath();
            if (path == null) {
                continue;
            }
            if (isSkillReferencePath(path) && !hasReadReferenceFile(path, observations)) {
                // 模板尚未读取过：属于写代码前的必要前置步骤，给予豁免
                return true;
            }
        }
        return false;
    }

    /**
     * 判断路径是否为 Skill 参考模板路径（docs/ 目录下的 .md 文件）。
     *
     * <p>兼容相对路径（以 docs/ 开头）与绝对路径（路径中包含 /docs/ 段且以 .md 结尾）。</p >
     *
     * @param path 文件路径
     * @return 若为 Skill 参考模板路径则返回 true
     */
    private boolean isSkillReferencePath(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.trim().replace('\\', '/');
        // 相对路径：以 docs/ 开头
        if (normalized.startsWith("docs/")) {
            return true;
        }
        // 绝对路径：路径中是否包含 "/docs/" 目录段，且文件名以 .md 结尾（参考模板）
        return normalized.contains("/docs/") && normalized.endsWith(".md");
    }

    /**
     * 判断指定参考模板文件是否已在观察结果中成功读取过。
     *
     * <p>通过比较文件名判断，兼容相对路径与绝对路径形式。</p >
     *
     * @param path         计划中 read_file 的模板路径
     * @param observations 观察结果
     * @return 若该模板已成功读取过则返回 true
     */
    private boolean hasReadReferenceFile(String path, List<Observation> observations) {
        if (observations == null || observations.isEmpty()) {
            return false;
        }
        String targetName = new File(path.trim()).getName();
        if (targetName.isEmpty()) {
            return false;
        }
        for (Observation o : observations) {
            if (!"read_file".equals(o.getTool())
                    || !o.isSuccess()
                    || !(o.getResult() instanceof FileContent)) {
                continue;
            }
            FileContent content = (FileContent) o.getResult();
            String readPath = content.getPath();
            if (readPath == null) {
                continue;
            }
            String readName = new File(readPath).getName();
            if (targetName.equals(readName)) {
                return true;
            }
        }
        return false;
    }


    private CodePlan repairExistingFileWritePlan(AgentContext context, CodePlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return plan;
        }
        for (PlanStep step : plan.getSteps()) {
            if (!isCreateTool(step.getTool()) || !(step.getInput() instanceof ToolInput)) {
                continue;
            }
            ToolInput input = (ToolInput) step.getInput();
            if (!isExistingFile(input.getPath())) {
                continue;
            }
            String newText = writeContent(input);
            if (newText != null) {
                // 文件已存在但 write_file 已放开覆盖限制，WriteFileTool 支持直接覆盖。
                // 不再转为 patch_file：LLM 生成的 newText 常为完整文件内容，
                // 转 patch_file 需 oldText 完全匹配（含空格/换行/缩进），
                // 一旦不匹配就降级 read_file 导致任务空转直至耗尽迭代。
                // 直接保留 write_file 覆盖写入，WriteFileTool / ToolExecutor / PlanValidator
                // 均已放开覆盖已存在文件的限制。
                log.warn(
                        "write existing file detected, keep write_file for direct overwrite path={}",
                        input.getPath()
                );
                // 若 LLM 把内容放在 newText 但 write_file 需要 content 字段，
                // 这里做一次兜底迁移。
                if (input.getContent() == null && input.getNewText() != null) {
                    input.setContent(input.getNewText());
                    input.setNewText(null);
                }
                continue;
            }
            // newText 为 null（LLM 未生成任何写入内容）时：
            // write_file 无法执行，降级为 read_file 让 LLM 读取文件内容后下一轮生成写入动作。
            // 注意：这里只降级当前步骤为 read_file，保留计划中其他步骤不被丢弃。
            log.warn(
                    "write existing file detected but no write content found,"
                            + " fallback to read_file for LLM to write later path={}",
                    input.getPath()
            );
            step.setTool("read_file");
            input.setOldText(null);
            input.setNewText(null);
            input.setContent(null);
            continue;
        }
        return plan;
    }

    private String writeContent(ToolInput input) {
        if (input.getNewText() != null) {
            return input.getNewText();
        }
        return input.getContent();
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

    private CodePlan repairInvalidPatchPlan(
            AgentContext context,
            CodePlan plan
    ) {

        if (plan == null
                || plan.getSteps() == null
                || plan.getSteps().isEmpty()) {

            return plan;

        }

        for (PlanStep step : plan.getSteps()) {

            if (!"patch_file".equals(step.getTool())
                    || !(step.getInput() instanceof ToolInput)) {

                continue;

            }

            ToolInput input =
                    (ToolInput) step.getInput();

            if (shouldRepairPatchOldText(input)) {

                // oldText 无效时，优先检查 newText 是否有效。
                // 若 newText 有效（非空、非占位符），说明 LLM 已生成完整的目标代码内容
                // （常见于按模板生成全新文件或整体重写已有文件的场景），
                // 此时将 patch_file 转为 write_file 直接写入，保留 LLM 生成的代码，
                // 而非降级为 read_file 丢弃代码导致任务空转。
                if (isValidPatchNewText(input.getNewText())) {
                    // oldText 无效但 newText 有效：LLM 已生成完整目标代码内容，
                    // 转为 write_file 直接覆盖写入（write_file 已放开已存在文件限制），
                    // 保留 LLM 生成的代码，避免降级 read_file 导致任务空转直至耗尽迭代。
                    log.warn(
                            "invalid patch oldText but valid newText detected,"
                                    + " convert patch_file to write_file path={}",
                            input.getPath()
                    );
                    step.setTool("write_file");
                    // WriteFileTool 优先读取 content 字段作为写入内容，
                    // 因此将原 patch_file 的 newText 同步设置到 content 字段。
                    input.setContent(input.getNewText());
                    // 转为 write_file 后清除无效的 oldText，避免 WriteFileTool 误走 patch 分支
                    input.setOldText(null);
                    continue;
                }

                // oldText 和 newText 均无效，才降级为 read_file 让 LLM 重新读取后重试
                log.warn(
                        "invalid patch oldText detected, fallback to read_file path={}",
                        input.getPath()
                );

                return CodePlan.builder()
                        .task(context.getTask())
                        .steps(singleReadStep(input.getPath()))
                        .build();

            }

        }

        return plan;

    }

    private boolean shouldRepairPatchOldText(ToolInput input) {
        if (input == null || input.getPath() == null || isEmptyExistingFile(input.getPath())) {
            return false;
        }
        return isInvalidPatchOldText(input.getOldText())
                || !oldTextExists(input.getPath(), input.getOldText());
    }

    private boolean oldTextExists(String path, String oldText) {
        if (path == null || oldText == null) {
            return false;
        }
        try {
            File file = workspaceService.resolve(path);
            if (!file.exists() || !file.isFile()) {
                return true;
            }
            String content = new String(
                    Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8
            );

            // 多种匹配策略，提高容错性
            return content.contains(oldText)
                    || normalizeLineSeparator(content).contains(normalizeLineSeparator(oldText))
                    || flexibleTextMatch(content, oldText);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean flexibleTextMatch(String content, String oldText) {
        // 宽松文本匹配，允许一定的格式差异
        String normalizedContent = normalizeText(content);
        String normalizedOldText = normalizeText(oldText);

        return normalizedContent.contains(normalizedOldText);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        // 移除多余空格、制表符，统一格式
        return text.replaceAll("\\s+", " ")
                .replaceAll("\\t", "    ")
                .trim();
    }

    private String normalizeLineSeparator(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
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

    private boolean isEmptyExistingFile(String path) {
        if (path == null) {
            return false;
        }
        try {
            File file = workspaceService.resolve(path);
            return file.exists() && file.isFile() && file.length() == 0;
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

        // 3) 如果 Skill 指定了目标类名，但以上步骤未找到匹配的路径，
        //    放宽限制：从 search_code 结果中提取任意真实 .java 路径
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

    /**
     * 构造一个"读取真实目标 .java 文件"的计划，用于打破 LLM 反复读取编造路径的死循环。
     *
     * <p>当 forced rewrite 后 LLM 仍返回纯只读计划、且计划中还在读取一个不存在的路径时，
     * 用刚从观察 / PROJECT_INDEX 提取到的真实目标路径替换计划中原有的 read_file 路径，
     * 返回修正后的计划。这样下一轮 AgentLoop 执行该计划后，真实文件内容会进入 OBSERVATIONS，
     * LLM 便能在后续轮次基于真实内容生成 patch_file 写入动作。</p >
     *
     * @param observations 最近观察结果
     * @param context      上下文
     * @param originalPlan 原始纯只读计划（用于继承其中读到的真实路径）
     * @return 修正后的读取计划；若无法定位真实路径则返回 null（由调用方保留原计划）
     */
    private CodePlan buildRealTargetReadPlan(
            List<Observation> observations,
            AgentContext context,
            CodePlan originalPlan
    ) {
        String realPath = extractRealTargetPath(observations, context);
        if (realPath == null) {
            return null;
        }

        // 收集原始计划中尚未读取过的模板分块 read_file 步骤，
        // 避免缩减计划时丢弃模板读取步骤导致 LLM 永远看不到模板内容。
        List<PlanStep> unreadTemplateSteps = new ArrayList<>();
        if (originalPlan != null && originalPlan.getSteps() != null) {
            for (PlanStep step : originalPlan.getSteps()) {
                if (!"read_file".equals(step.getTool())
                        || !(step.getInput() instanceof ToolInput)) {
                    continue;
                }
                ToolInput input = (ToolInput) step.getInput();
                String path = input.getPath();
                if (path == null || !isSkillReferencePath(path)) {
                    continue;
                }
                // 用完整 observations 判断模板是否已读过，避免截断导致误判
                if (!hasReadReferenceFile(path, context.getObservations())) {
                    unreadTemplateSteps.add(step);
                }
            }
        }

        // 构建最终步骤列表：先读取真实目标文件，再追加尚未读取的模板分块步骤。
        // 这样既保证 LLM 能看到目标文件真实内容，又不丢失模板分块的读取，
        // 避免模板被反复丢弃导致 LLM 永远看不到模板内容而陷入只读死循环。
        List<PlanStep> steps = new ArrayList<>();
        int order = 1;

        // 第一步：读取真实目标文件
        steps.add(PlanStep.builder()
                .order(order++)
                .tool("read_file")
                .input(ToolInput.builder()
                        .path(realPath)
                        .startLine(1)
                        .endLine(1000)
                        .build())
                .description("Read real target file: " + realPath)
                .build());

        // 后续步骤：追加尚未读取的模板分块 read_file
        for (PlanStep templateStep : unreadTemplateSteps) {
            steps.add(PlanStep.builder()
                    .order(order++)
                    .tool("read_file")
                    .input(templateStep.getInput())
                    .description(templateStep.getDescription())
                    .build());
        }

        return CodePlan.builder()
                .task(originalPlan == null ? context.getTask() : originalPlan.getTask())
                .steps(steps)
                .build();
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

    /**
     * 判断 patch_file 的 newText 是否为有效的目标代码内容。
     *
     * <p>当 oldText 无效但 newText 有效时，说明 LLM 已生成完整的目标代码
     * （常见于按模板生成全新文件或整体重写已有文件的场景），
     * 此时将 patch_file 转为 write_file 直接写入，保留 LLM 生成的代码，
     * 而非降级为 read_file 丢弃代码导致任务空转。</p >
     *
     * @param newText patch_file 的 newText 字段
     * @return 若 newText 为有效的代码内容则返回 true
     */
    private boolean isValidPatchNewText(String newText) {
        if (newText == null || newText.trim().isEmpty()) {
            return false;
        }
        String normalized = newText.trim();
        // 排除占位符模式
        return !normalized.equals("{{")
                && !normalized.equals("}}")
                && !normalized.equals("READ_FILE_CONTENT")
                && !normalized.equals("...")
                && !normalized.matches("^\\{\\{.*\\}\\}$")
                && !normalized.equals("");
    }

    private CodePlan parse(String task, String json) {
        try {
            if (json == null
                    ||
                    json.trim().isEmpty()) {

                throw new RuntimeException(
                        "model response is empty"
                );

            }// 兼容新模型输出的 trailing comma（对象/数组最后一个元素后多余的逗号），
            // 对合法 JSON 无副作用，不影响旧模型解析。
            // 同时清理模型可能误拼进字符串值内部的 XML 标签（如 <quick_command>...</quick_command>），
            // 修复字符串值内部未转义的反斜杠（如 Windows 路径 D:\FlinkTest\demo），
            // 以及将字符串值内部真实存在的控制字符（换行/回车/制表符）转义为 JSON 转义序列，
            // 避免因这些内容导致整个 plan 解析失败。
            String cleanedJson = fixInvalidBackslashEscapes(
                    escapeControlCharsInStrings(
                            stripXmlTagsInStrings(
                                    removeTrailingCommas(extractJson(json))
                            )
                    )
            );

            // 部分模型在生成 JSON 时可能遗漏某个步骤对象的闭合 '}'，
            // 导致后续 '{' 被解析器误判为嵌套对象，抛出
            // "Unexpected character ('{')": was expecting double-quote to start field name"。
            // 先按标准方式解析；若因对象未闭合失败，再尝试结构修复后重试，对合法 JSON 无副作用。
            JsonNode root;
            try {
                root = objectMapper.readTree(cleanedJson);
            } catch (JsonParseException e) {
                if (isUnclosedObjectError(e)) {
                    String repaired = repairUnclosedObjects(cleanedJson);
                    root = objectMapper.readTree(repaired);
                } else {
                    throw e;
                }
            }
            validateModelResponse(root);

            List<PlanStep> steps = new ArrayList<>();
            int index = 1;
            JsonNode stepArray;

            if (root.has("steps")) {
                stepArray = root.get("steps");
            } else if (root.has("plan")) {
                stepArray = root.get("plan");
            } else {
                throw new RuntimeException("plan field not found");
            }

            for (JsonNode node : stepArray) {
                // 单步容错：模型偶发会输出结构不规范的步骤（如 tool 缺失、或 keyword/path 未包进 input，
                // 或 input 是字符串等无法反序列化为 ToolInput）。为避免单个坏步骤让整个计划解析失败
                // 并中断任务，这里对该步骤做兜底：可修复则修复，不可修复则跳过并记录日志。
                try {
                    JsonNode inputNode;
                    if (node.has("input")) {
                        inputNode = node.get("input");
                    } else if (node.has("args")) {
                        inputNode = node.get("args");
                    } else {
                        inputNode = objectMapper.createObjectNode();
                    }

                    // 兼容模型把 keyword/path/limit 等直接放在 tool 平级（漏包 input）的情况：
                    // 尝试把节点自身作为输入；若可反序列化成 ToolInput 且至少包含 path 或 keyword，
                    // 则视为合法的 key-param 平铺写法，直接作为该步骤的输入。
                    if (!node.has("input") && !node.has("args")) {
                        try {
                            ToolInput flatInput = objectMapper.treeToValue(node, ToolInput.class);
                            if (flatInput != null
                                    && (flatInput.getPath() != null || flatInput.getKeyword() != null)) {
                                inputNode = node;
                            }
                        } catch (Exception ignore) {
                            // 平铺节点无法转为 ToolInput，回退到空对象输入，保证步骤不因此失败
                        }
                    }

                    if (!node.has("tool") || node.get("tool").isNull()
                            || node.get("tool").asText().trim().isEmpty()) {
                        // tool 缺失无法执行，跳过该坏步骤，避免整个计划解析失败中断任务
                        log.warn("plan step missing tool, skip step={}", node.size());
                        continue;
                    }

                    ToolInput input = objectMapper.treeToValue(inputNode, ToolInput.class);

                    steps.add(
                            PlanStep.builder()
                                    .order(index++)
                                    .tool(node.get("tool").asText())
                                    .input(input)
                                    .description(node.has("description") ? node.get("description").asText() : "")
                                    .build()
                    );
                } catch (Exception singleStepError) {
                    // 单步反序列化失败（例如 input 为字符串无法转 ToolInput），跳过该坏步骤，
                    // 保留其余正确步骤继续执行，避免单个模型输出异常导致整个任务 parse plan failed。
                    log.warn("skip invalid plan step, node={}, err={}",
                            node.size(), singleStepError.getMessage());
                }
            }
            return CodePlan.builder()
                    .task(task)
                    .steps(steps)
                    .build();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // 模型偶发会输出无法修复的非法 JSON（例如 patch_file 的 oldText/newText 字段内含
            // 超大真实源码，其反斜杠/引号/花括号转义错乱，字符串级修复器无法可靠兜底，二次 readTree
            // 仍失败）。此时若继续抛异常会导致整个 Agent 任务以 parse plan failed 中断。
            // 稳健做法：记录告警并降级返回空计划，让上层走 isEmptyPlan 兜底（不硬中断任务，
            // 由后续迭代/修复流程继续推进或正常收敛），避免一次模型输出格式问题击穿整个任务。
            if (isRecoverableFormatterError(e)) {
                log.warn("plan json irrecoverable, fallback to empty plan. err={}", e.getMessage());
                return CodePlan.builder()
                        .task(task)
                        .steps(new ArrayList<>())
                        .build();
            }
            throw new RuntimeException("parse plan failed:" + json, e);
        }
    }

    /**
     * 判断是否为"模型输出不可修复的非法 JSON"类错误。
     *
     * <p>这类错误通常源于模型在超大字符串字段（如 patch_file 的 oldText/newText，内含完整源码）
     * 中的反斜杠/引号/花括号转义错乱，导致对象结构被破坏，字符串级的容错修复器既无法恢复、
     * 也不代表模型调用本身失败。此时应降级返回空计划而非中断任务。</p >
     *
     * @param e 解析阶段捕获的异常
     * @return 若属于不可修复的格式/结构类错误则返回 true
     */
    private boolean isRecoverableFormatterError(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof JsonParseException) {
                return true;
            }
            if (cause instanceof com.fasterxml.jackson.databind.JsonMappingException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void validateModelResponse(
            JsonNode root
    ) {


        if (!root.has("error")) {

            return;

        }


        JsonNode error =
                root.get("error");


        String message =
                error.has("message")
                        ? error.get("message").asText()
                        : error.toString();


        throw new RuntimeException(
                "model call failed:"
                        +
                        message
        );

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
                return "当前阶段 REPAIR\n" +
                        "根据 VERIFY_ERRORS 中的验证错误（编译错误/测试失败/断言失败）修复代码。\n" +
                        "修复完成后系统会自动重新执行 VERIFY(编译+测试)，形成 修复->重新测试 闭环，直到测试通过。\n\n" +
                        "允许:\n" +
                        "- read_file\n" +
                        "- write_file\n" +
                        "- patch_file\n\n" +
                        "规则:\n" +
                        "1. 修复已有文件必须先read_file再write_file或patch_file。\n" +
                        "2. write_file 支持直接覆盖已存在文件，内容是完整的新文件内容。\n" +
                        "3. patch_file 用于局部修改（oldText/newText 精确替换），oldText 必须与文件内容完全一致（含空格/换行/缩进）。\n" +
                        "4. 如果担心 oldText 难以精确匹配，优先使用 write_file 直接覆盖整个文件内容。\n" +
                        "5. 必须针对 VERIFY_ERRORS 中的具体错误进行修复，不要修复无关代码。\n" +
                        "6. 如果是编译错误，修复对应的业务代码（src/main）或测试代码（src/test）。\n" +
                        "7. 如果是测试断言失败，检查测试类期望值与业务代码实际行为是否一致，修复错误的一方。";

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
        // ============ CODING 双子行程（S1 业务代码 / S2 测试类）分支 ============
        // S2_TEST：本调次只允许生成测试类，注入"业务代码已完成（真实签名可读）"
        // 提示，仅允许 write_file / patch_file 写到 src/test 下新建或修改测试类，
        // 严禁修改或重写任何业务代码（src/main）。
        if (context.getCodingTrack() == CodingTrack.S2_TEST) {
            return buildS2TestPhaseRules(context);
        }
        // S1_BUSINESS：本调次只生成业务代码（src/main），测试类由后续 S2 子行程独立生成。
        StringBuilder sb = new StringBuilder();
        sb.append("当前阶段 CODING（S1 业务代码）\n\n");
        sb.append("本次仅允许生成业务代码（src/main 目录），测试类由后续流程独立生成，无需也不得在本调次生成或修改测试类。\n\n");
        sb.append("工具规则:\n\n");

        sb.append("新增文件:\n");
        sb.append("- 使用 write_file（content 为完整文件内容）。\n\n");

        sb.append("修改已有文件（关键）:\n");
        sb.append("- write_file 支持直接覆盖已存在文件，content 传完整的新文件内容，无需精确匹配旧内容。\n");
        sb.append("- patch_file 用于局部修改（oldText/newText 精确替换），oldText 必须与文件内容完全一致（含空格/换行/缩进）。\n");
        sb.append("- 若难以精确构造 oldText（如文件较长、缩进复杂），优先使用 write_file 直接覆盖整个文件内容。\n\n");

        sb.append("patch_file前置要求（仅 patch_file 需要）:\n");
        sb.append("- 必须先执行 read_file 获取 oldText。\n");
        sb.append("- 禁止编造 oldText。\n");
        sb.append("- patch_file 没有 read_file 结果则无效。\n\n");

        sb.append("执行顺序:\n");
        sb.append("1. 修改已有文件且希望保留局部修改：先 read_file 读取完整文件，再用 patch_file。\n");
        sb.append("2. 创建新增文件或整文件覆盖：直接使用 write_file（无需 read_file 前置）。\n\n");

        // ============ 动态引导：根据已读取结果推进到写入阶段 ============
        List<Observation> observations = getPlannerObservations(context);
        boolean hasTargetRead = hasSuccessfulReadOfTarget(observations, context);
        boolean hasPomRead = hasSuccessfulReadOfPom(observations);

        if (hasTargetRead) {
            sb.append("动态提示:\n");
            sb.append("目标文件已经通过read_file成功读取，内容已在上方OBSERVATIONS中提供。\n");
            sb.append("禁止再次read_file同一目标文件。\n");
            sb.append("必须立即基于OBSERVATIONS中的真实内容生成patch_file(Target文件)。\n");
            sb.append("patch_file的oldText必须来自OBSERVATIONS中read_file返回的真实内容，newText为修改后的完整文件内容。\n\n");
        }

        if (hasPomRead) {
            sb.append("动态提示(依赖):\n");
            sb.append("pom.xml已经通过read_file成功读取。\n");
            if (!hasTargetRead) {
                sb.append("请继续处理目标文件的修改。\n");
            }
            sb.append("如果Skill要求新增依赖，检查pom.xml是否已包含该依赖；若缺失，必须生成patch_file(pom.xml)添加依赖，再修改目标文件。\n\n");
        }

        if (!hasTargetRead) {
            sb.append("当前尚未读取目标文件。\n");
            sb.append("第一步必须先read_file(Target文件)读取完整内容，之后必须立即生成patch_file(Target文件)完成修改。\n");
            sb.append("禁止只生成read_file而不生成patch_file。\n");
            sb.append("如果Skill要求新增依赖，还应先read_file(pom.xml)检查依赖。\n\n");
        }

        // ============ 技能参考模板遵循引导：确保按模板结构生成外调代码 ============
        // 若命中的 Skill（如预测接口 predict-api / 外调接口 external-api）要求以 docs/ 下的
        // .md 文件作为编写参考模板，则必须先将模板 read_file 进 OBSERVATIONS，并严格参照其
        // 结构生成代码（固定实体类 / 统一 Handler / @JsonProperty 字段映射 / eventData 取值赋值等）。
        SbAppendTemplateFollowRules(sb, context);

        return sb.toString();
    }

    /**
     * 向 CODING 阶段规则中追加技能参考模板的遵循引导。
     *
     * <p>当当前任务命中一个携带 {@code docs/xxx.md} 参考模板的 Skill（例如 predict-api 要求按
     * {@code docs/predict-call-template.md} 生成外调接口代码）时，往规则末尾注入一段强约束：
     * <ul>
     *   <li>若模板尚未被 read_file 读取，要求必须先读取模板，禁止凭印象编写外调代码；</li>
     *   <li>若模板已被读取，则要求严格参照模板中的固定写法生成代码，使模板真正成为编码规范而非仅是背景文本。</li>
     * </ul>
     * 这样能使 CODING 阶段"读到了模板"与"按模板生成"形成闭环，避免模板被读后仍然散落发挥。</p >
     *
     * @param sb      规则文本构建器
     * @param context 上下文
     */
    private void SbAppendTemplateFollowRules(StringBuilder sb, AgentContext context) {
        try {
            List<Skill> skills = skillMatcher.match(context.getTask());
            if (skills == null || skills.isEmpty()) {
                return;
            }
            Set<String> templatePaths = extractSkillTemplatePaths(skills);
            if (templatePaths.isEmpty()) {
                return;
            }
            List<Observation> observations = getPlannerObservations(context);
            sb.append("技能参考模板遵循规则（必须遵守）:\n");
            for (String path : templatePaths) {
                sb.append("- 本任务命中 Skill 要求以模板 ").append(path).append(" 作为外调接口代码的编写参考。\n");
                if (hasReadReferenceFile(path, observations)) {
                    sb.append("  · 该模板已通过 read_file 读取，其内容在 OBSERVATIONS 中。请严格参照模板的固定结构生成代码："
                            + "固定实体类（@JsonProperty 字段映射）、统一 Client/Handler 调用、事件字段取值与写回，"
                            + "禁止散落 Map 拼请求体或业务类直接调 HTTP、禁止改写实体字段大小写。\n");
                } else {
                    sb.append("  · 该模板当前尚未被读取。在编写任何外调接口代码（实体类 / Handler / 取值赋值）之前，"
                            + "必须先 read_file 读取该模板，再严格按模板结构生成；禁止凭印象或编造路径直接写外调代码。\n");
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            // 模板遵循引导属于辅助约束，异常不应阻断正常编码规则生成。
            log.warn("build template follow rules warn", e);
        }
    }

    /**
     * 构建 CODING 阶段 S2（测试类生成子行程）的专用规则。
     *
     * <p>本子行程假设 S1 已生成并落盘业务代码，因此注入"业务代码已完成、真实签名可读"的
     * 提示，仅允许生成/修改 src/test 目录下的测试类，严禁触碰 src/main 业务代码。</p >
     *
     * @param context 上下文
     * @return S2 阶段规则文本
     */
    private String buildS2TestPhaseRules(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前阶段 CODING（S2 测试类）\n\n");
        sb.append("S1 业务代码已生成并落盘（src/main），真实的方法签名可通过 search_code / read_file 读取。\n");
        sb.append("本调次的目标是：为已完成的业务逻辑生成 JUnit 测试类。\n\n");
        sb.append("工具规则:\n\n");

        sb.append("新增测试文件:\n");
        sb.append("- 使用 write_file 创建 src/test/** 下的测试类（类名以 Test 结尾）。\n\n");

        sb.append("修改已有测试文件:\n");
        sb.append("- write_file 支持直接覆盖已存在文件，content 传完整的新文件内容，无需精确匹配旧内容。\n");
        sb.append("- 或先 read_file 获取 oldText，再用 patch_file 局部修改。\n");
        sb.append("- 禁止编造 oldText。\n\n");

        sb.append("禁止:\n");
        sb.append("- 使用 write_file 整文件覆盖 src/main 目录下的业务代码（但允许 patch_file 局部修改 src/main 以修复测试发现的编译/逻辑错误）。\n");
        sb.append("- 编造业务方法签名：测试引用的方法必须来自 search_code / read_file 读取到的真实业务代码。\n\n");

        sb.append("执行顺序:\n");
        sb.append("1. 先 search_code / read_file 待测的业务类，确认真实方法签名。\n");
        sb.append("2. 新建测试类使用 write_file 写入 src/test 对应目录。\n");
        sb.append("3. 修改已有测试类可使用 write_file 直接覆盖，或先 read_file 再用 patch_file 局部修改。\n\n");

        sb.append("动态提示:\n");
        if (context.getVerifyErrors() != null && !context.getVerifyErrors().isEmpty()) {
            sb.append("已知测试失败信息已被注入 VERIFY_ERRORS，请针对失败原因修正对应测试类。\n\n");
        } else {
            sb.append("当前无历史失败信息，按业务类真实签名生成完整、可编译、有断言的测试类。\n\n");
        }

        return sb.toString();
    }

    /**
     * 构建"强制重写"阶段规则（方案B）。
     *
     * <p>当 CODING 阶段已读取目标文件，但 LLM 仍输出纯只读计划时，使用本方法生成
     * 强约束指令替换原有 PHASE_RULES，明确告知 LLM 必须生成写入动作，否则计划被判无效。</p >
     *
     * @param context                上下文
     * @param readingNonExistentPath 是否检测到计划正在读取一个不存在的路径
     * @return 强约束的 CODING 阶段规则文本
     */
    private String buildForcedRewriteRules(AgentContext context, boolean readingNonExistentPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前阶段 CODING（强制重写，必须输出写入动作）\n\n");
        if (readingNonExistentPath) {
            sb.append("你上一轮生成的计划仅包含只读操作，且读取了【不存在】的文件路径，"
                    + "该路径无法读取到任何有效内容，属于无效计划。\n");
        } else {
            sb.append("你上一轮生成的计划仅包含只读操作（read_file），未包含任何写入动作，属于无效计划。\n");
            sb.append("目标文件已经通过 read_file 成功读取，其完整真实内容已在上方 OBSERVATIONS 中提供。\n\n");
        }

        sb.append("【强制要求】本轮必须生成对目标文件的写入动作，禁止只输出 read_file：\n");
        sb.append("1. 必须至少包含一个 patch_file（修改已有文件）或 write_file（新增文件）步骤。\n");
        sb.append("2. 修改已有文件（如目标类 DataPrepEventHandler）必须使用 patch_file，且：\n");
        sb.append("   - oldText 必须来自 OBSERVATIONS 中 read_file 返回的真实内容片段；\n");
        sb.append("   - newText 为基于 oldText 修改后的完整内容。\n");
        sb.append("3. 如果计划中 read_file 的路径在上方 OBSERVATIONS 中读取失败/不存在，"
                + "【禁止】再次读取该不存在的路径。必须改用 search_code 在 PROJECT_INDEX 中定位真实存在的目标类路径，\n"
                + "   或直接基于已有的 OBSERVATIONS 内容生成写入动作。\n");
        sb.append("4. 如果 Skill 要求新增依赖（pom.xml），还必须生成 patch_file(pom.xml) 添加依赖。\n");
        sb.append("5. 禁止再次生成与上一轮完全相同的只读计划；禁止输出只读计划。\n\n");

        sb.append("如果仍然只输出 read_file（尤其是读取不存在的路径），该计划将被判定为无效并丢弃。\n");
        return sb.toString();
    }


    /**
     * 判断最近观察结果中是否存在目标文件的 read_file 成功结果。
     *
     * @param observations 最近观察结果
     * @return 是否已成功读取目标文件
     */
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
     * 判断计划中是否存在正在读取一个"不存在路径"的 read_file 步骤。
     *
     * <p>当 LLM 在 CODING 阶段编造了不存在的文件路径（如 {@code CashAdvanceDecisionFlowExtension.java}）
     * 并反复 read_file 时，该路径在最近的观察结果中会表现为 read_file 失败（success=false，error 非空）。
     * 检测到这种情况说明计划属于无效的"编造路径只读循环"，应强制重写引导 LLM 定位真实路径。</p >
     *
     * <p>兼容输入为绝对路径（如 {@code D:\\xxx\\yyy.java}）与经反斜杠/正斜杠混合表示的路径：
     * 通过提取路径尾部的文件名来判断是否与失败的 read_file 指向同一文件。</p >
     *
     * @param plan         代码计划
     * @param observations 最近观察结果（用于判断路径是否读取失败）
     * @return 若计划中存在读取失败（路径不存在）的 read_file 步骤则返回 true
     */
    private boolean containsNonExistentRead(CodePlan plan, List<Observation> observations) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return false;
        }
        if (observations == null || observations.isEmpty()) {
            return false;
        }
        for (PlanStep step : plan.getSteps()) {
            if (!"read_file".equals(step.getTool())
                    || !(step.getInput() instanceof ToolInput)) {
                continue;
            }
            ToolInput input = (ToolInput) step.getInput();
            String path = input.getPath();
            if (path == null || path.trim().isEmpty()) {
                continue;
            }
            String plannedName = new File(path.trim()).getName();
            if (plannedName.isEmpty()) {
                continue;
            }
            if (hasFailedReadOfPath(observations, plannedName)) {
                // 该计划正在读取一个在观察中读取失败的路径（路径不存在）
                return true;
            }
        }
        return false;
    }

    /**
     * 判断计划中是否包含"编造或错误的 .java 文件路径"——即 read_file 指向一个 .java 文件，
     * 但该路径属于以下任一情况：
     * <ol>
     *     <li>编造的不存在路径：文件既不在 PROJECT_INDEX 中，也不是 docs/ 模板，且 workspace 中不存在</li>
     *     <li>存在但错误的路径：文件真实存在，但文件名不匹配 Skill 指定的目标类名
     *         （如 Skill 指定目标类为 {@code DataPrepEventHandler}，但 LLM 读取了
     *         {@code CashAdvanceDecisionFlowExtension.java}）</li>
     * </ol>
     *
     * <p>该方法不依赖观察结果中的读取失败记录，因此在第 1 轮 CODING 时即可检测到
     * LLM 编造的不存在路径或读取错误文件，避免 LLM 陷入"反复读取编造/错误路径"的只读死循环。</p >
     *
     * <p>判断逻辑：</p >
     * <ol>
     *     <li>计划中的 read_file 路径以 .java 结尾（排除 .md 模板、配置文件等）</li>
     *     <li>该路径不是 docs/ 目录下的参考模板</li>
     *     <li>检测编造路径：该路径不在 PROJECT_INDEX 中且 workspace 中不存在 → 编造</li>
     *     <li>检测错误路径：Skill 指定了目标类名，但文件名不匹配目标类名 → 错误文件</li>
     * </ol>
     *
     * @param plan              代码计划
     * @param context           上下文（用于获取 PROJECT_INDEX 和 Skill 目标类名）
     * @param targetAlreadyRead 目标文件是否已被成功读取过
     * @return 若计划中存在编造或错误的 .java 路径则返回 true
     */
    private boolean containsFabricatedJavaRead(CodePlan plan, AgentContext context, boolean targetAlreadyRead) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return false;
        }
        // 收集 PROJECT_INDEX 中所有 .java 文件名，用于快速判断路径是否在索引中
        Set<String> indexedJavaNames = new LinkedHashSet<>();

        if (context.getProjectIndex() != null
                && context.getProjectIndex().getFiles() != null) {
            for (ProjectFile pf : context.getProjectIndex().getFiles()) {
                if (pf.getPath() != null) {
                    String normalized = pf.getPath().replace('\\', '/');
                    if (normalized.endsWith(".java")) {
                        indexedJavaNames.add(new File(pf.getPath()).getName());
                    }
                }
            }
        }
        // 获取 Skill 指定的目标类名，用于检测"存在但错误的文件"
        String targetClassName = getSkillTargetClassName(context);
        for (PlanStep step : plan.getSteps()) {
            if (!"read_file".equals(step.getTool())
                    || !(step.getInput() instanceof ToolInput)) {
                continue;
            }
            ToolInput input = (ToolInput) step.getInput();
            String path = input.getPath();
            if (path == null || path.trim().isEmpty()) {
                continue;
            }
            String normalized = path.trim().replace('\\', '/');
            // 仅检查 .java 文件
            if (!normalized.endsWith(".java")) {
                continue;
            }
            // 排除 docs/ 目录下的参考模板（虽然 .md 才是模板，但防御性排除 docs/ 下的 .java）
            if (normalized.startsWith("docs/") || normalized.contains("/docs/")) {
                continue;
            }
            String fileName = new File(path.trim()).getName();
            if (fileName.isEmpty()) {
                continue;
            }
            // 检测"存在但错误的文件"：Skill 指定了目标类名，但文件名不匹配
            // 例如 Skill 指定目标类为 DataPrepEventHandler，但 LLM 读取了 CashAdvanceDecisionFlowExtension.java
            // 前置条件：仅当目标文件尚未被成功读取过（targetAlreadyRead=false）时才检测。
            // 如果目标文件已读取过，LLM 可能在读取其他辅助 .java 文件（如父类、参考实现），
            // 此时不应触发强制重写打断 LLM 的正常"读取→写入"流程。
            // 如果目标文件尚未读取过，且 LLM 读取了非目标类名的 .java 文件，
            // 说明 LLM 定位到了错误文件，需要通过 extractRealTargetPath 注入正确路径引导纠正。
            if (!targetAlreadyRead
                    && targetClassName != null && !targetClassName.isEmpty()
                    && !matchesSkillTarget(fileName, targetClassName)) {
                log.warn("wrong target java file detected in plan: {} (expected: {}.java, targetNotRead yet)", path, targetClassName);
                return true;
            }
            // 如果文件名在 PROJECT_INDEX 中，说明路径真实存在，不是编造的
            if (indexedJavaNames.contains(fileName)) {
                continue;
            }
            // 如果文件在 workspace 中实际存在，也不是编造的
            if (isRealJavaSource(path.trim())) {
                continue;
            }
            // 文件既不在 PROJECT_INDEX 中，也不在 workspace 中实际存在 → 编造路径
            log.warn("fabricated java path detected in plan: {}", path);
            return true;
        }
        return false;
    }

    /**
     * 判断观察结果中是否存在指定文件名对应的 read_file 失败记录。
     *
     * @param observations 观察结果
     * @param fileName     计划中 read_file 的目标文件名
     * @return 若存在对应文件名的失败读取得返回 true
     */
    private boolean hasFailedReadOfPath(List<Observation> observations, String fileName) {
        for (Observation o : observations) {
            if (!"read_file".equals(o.getTool()) || o.isSuccess()) {
                continue;
            }
            String failedName = failedReadInputName(o.getInput());
            if (failedName != null && failedName.equals(fileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从一条失败的 read_file 观察的 input 中提取目标文件名。
     *
     * <p>input 可能是 {@link ToolInput}（含 path 字段运行期填充），也可能是已序列化的
     * {@code Map} / 任意对象（LLM 回显）。这里通过反射或字符串序列化宽松提取路径与文件名。</p >
     *
     * @param input 观察的 input 字段
     * @return 目标文件名，无法提取时返回 null
     */
    private String failedReadInputName(Object input) {
        if (input == null) {
            return null;
        }
        String path = null;
        try {
            if (input instanceof ToolInput) {
                path = ((ToolInput) input).getPath();
            } else if (input instanceof Map) {
                Object p = ((Map<?, ?>) input).get("path");
                path = p == null ? null : String.valueOf(p);
            } else {
                // 兜底：序列化为 JSON 后尝试提取 path 字段
                String json = objectMapper.writeValueAsString(input);
                JsonNode node = objectMapper.readTree(json);
                JsonNode pathNode = node.get("path");
                if (pathNode != null && pathNode.isTextual()) {
                    path = pathNode.textValue();
                }
            }
        } catch (Exception e) {
            // 提取失败不阻塞判定
            return null;
        }
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        return new File(path.trim()).getName();
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

    /**
     * 判断最近观察结果中是否存在 pom.xml 的 read_file 成功结果。
     *
     * @param observations 最近观察结果
     * @return 是否已成功读取 pom.xml
     */
    private boolean hasSuccessfulReadOfPom(List<Observation> observations) {
        if (observations == null || observations.isEmpty()) {
            return false;
        }
        for (Observation o : observations) {
            if ("read_file".equals(o.getTool())
                    && o.isSuccess()
                    && o.getResult() instanceof FileContent) {
                FileContent content = (FileContent) o.getResult();
                String path = content.getPath();
                if (path != null && path.endsWith("pom.xml")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String buildObservations(AgentContext context) {
        List<Observation> list = getPlannerObservations(context);

        if (list == null || list.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        int start = Math.max(0, list.size() - 3);

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
                // 限制 read_file 结果长度，避免 prompt 总长度超过网关处理上限导致 504 超时。
                // 普通源码需要较多上下文（4000）；而 docs/ 下的技能参考模板若一次性全文注入
                // （如 predict-call-template.md 有 861 行 / 约 2.9 万字符）会导致 504 超时。
                // 因此模板已按类拆分为 docs/predict-call-template-parts/ 下的多个分块文件
                // （00 总览约束 / 01 请求实体类 / 02 配置类 / 03 HttpClient 工具类 / 04 调用
                // Handler / 05 入口改造），每个分块不超过约 1 万字符。
                // 分块模板必须【完整读取】才能让模型严格按模板逐类复制粘贴生成代码，
                // 截断会导致模型看不到完整类结构而自由发挥；故分块模板限长放宽到 12000
                // （可容纳最大分块全文），仅对未拆分的整份模板（docs/*.md）保留 1200 收紧限长。
                int maxResultLength;
                if ("read_file".equals(o.getTool())) {
                    if (isTemplatePartPath(o)) {
                        maxResultLength = 12000;
                    } else {
                        maxResultLength = isTemplatePath(o) ? 1200 : 4000;
                    }
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

    /**
     * 判断某条观察记录是否为"技能参考模板文件"（docs/ 目录下的 .md 文件）的读取结果。
     *
     * <p>用于在构建 OBSERVATIONS 上下文时，对技能参考模板这类"仅起结构指引作用、无需全文注入"
     * 的 read_file 结果做更小限长，避免 861 行等庞大的模板内容在 CODING 多轮调用中被反复注入，
     * 导致 prompt 总长度超过网关处理上限而触发 504 超时。</p >
     *
     * @param o 观察记录
     * @return 若该记录的 read_file 目标位于 docs/ 目录且以 .md 结尾则返回 true
     */
    private boolean isTemplatePath(Observation o) {
        try {
            if (o == null || o.getInput() == null) {
                return false;
            }
            Object input = o.getInput();
            String path = null;
            if (input instanceof ToolInput) {
                path = ((ToolInput) input).getPath();
            } else if (input instanceof Map) {
                Object p = ((Map<?, ?>) input).get("path");
                if (p != null) {
                    path = String.valueOf(p);
                }
            } else {
                String s = String.valueOf(input);
                if (s.contains("path")) {
                    return s.toLowerCase().contains("docs/") && s.toLowerCase().endsWith(".md");
                }
            }
            if (path == null || path.trim().isEmpty()) {
                return false;
            }
            String normalized = path.trim().replace('\\', '/').toLowerCase();
            return normalized.startsWith("docs/") && normalized.endsWith(".md");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断某条观察记录是否为"按类拆分后的模板分块文件"的读取结果。
     *
     * <p>完整模板（如 predict-call-template.md，861 行）单次注入会超出网关 token 上限，
     * 已按类拆分为 docs/predict-call-template-parts/ 目录下的多个分块文件。
     * 分块模板是模型逐类"复制粘贴"生成代码的直接依据，必须完整注入不截断，
     * 因此与整份模板的收紧限长（1200）区分开，单独识别。</p >
     *
     * @param o 观察记录
     * @return 若该记录的 read_file 目标位于 docs/predict-call-template-parts/ 目录则返回 true
     */
    private boolean isTemplatePartPath(Observation o) {
        try {
            if (o == null || o.getInput() == null) {
                return false;
            }
            Object input = o.getInput();
            String path = null;
            if (input instanceof ToolInput) {
                path = ((ToolInput) input).getPath();
            } else if (input instanceof Map) {
                Object p = ((Map<?, ?>) input).get("path");
                if (p != null) {
                    path = String.valueOf(p);
                }
            } else {
                String s = String.valueOf(input);
                return s.toLowerCase().contains("docs/predict-call-template-parts/");
            }
            if (path == null || path.trim().isEmpty()) {
                return false;
            }
            String normalized = path.trim().replace('\\', '/').toLowerCase();
            return normalized.contains("docs/predict-call-template-parts/");
        } catch (Exception e) {
            return false;
        }
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

    private String buildProjectSummary() {
        String summary = projectContextService.buildSummary();
        if (summary.length() > 1500) {
            return summary.substring(0, 1500);
        }
        return summary;
    }

    private List<Observation> getPlannerObservations(AgentContext context) {
        List<Observation> list;

        if (context.getLastObservations() != null && !context.getLastObservations().isEmpty()) {
            list = context.getLastObservations();
        } else {
            list = context.getObservations();
        }

        if (list == null) {
            return new ArrayList<>();
        }

        // 只保留最近3次。
        if (list.size() > 3) {
            return list.subList(list.size() - 3, list.size());
        }

        return list;
    }

    private String buildVerifyErrors(AgentContext context) {
        if (context.getVerifyErrors() == null) {
            return "";
        }
        return String.join("\n", context.getVerifyErrors());
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

    private String extractJson(String text) {
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 移除 JSON 中字符串字面量之外的"尾随逗号"（trailing comma），
     * 用于兼容部分新模型在对象/数组最后一个元素后输出多余逗号的情况。
     *
     * <p>实现要点：逐字符扫描并跟踪字符串字面量状态，仅处理结构层（非字符串内）
     * 的逗号，因此不会破坏 oldText/newText 等字段内容中的 Java 代码；对完全合法
     * 的 JSON（如旧模型输出）无副作用，不影响旧模型解析。</p >
     *
     * @param json 原始 JSON 字符串
     * @return 清理尾随逗号后的 JSON 字符串
     */
    private String removeTrailingCommas(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\') {
                    // 转义字符：连同下一个字符一起原样保留，避免把 \" 误判为字符串结束
                    if (i + 1 < json.length()) {
                        sb.append(json.charAt(i + 1));
                        i++;
                    }
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                    sb.append(c);
                } else if (c == ',') {
                    // 向后看（跳过空白），若下一个非空白字符是 } 或 ]，则为尾随逗号，跳过不输出
                    int j = i + 1;
                    while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
                        j++;
                    }
                    if (j < json.length()
                            && (json.charAt(j) == '}' || json.charAt(j) == ']')) {
                        continue;
                    }
                    sb.append(c);
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 移除 JSON 字符串字面量内部模型误插入的 XML 标签（如 <quick_command>...</quick_command>）。
     *
     * <p>部分模型在生成 JSON 时可能把输出格式标签错误地拼进字符串值内部，例如
     * {@code "src<quick_command>/test</quick_command>/java/..."}，导致标准 JSON
     * 解析失败。该方法逐字符扫描并跟踪字符串字面量状态，仅移除字符串值内部的
     * {@code <...>} / {@code </...>} 标签片段，不影响 JSON 结构本身；对不含标签的
     * 合法 JSON 无副作用，不影响旧模型解析。</p >
     *
     * @param json 原始 JSON 字符串
     * @return 清理字符串值内部 XML 标签后的 JSON 字符串
     */
    private String stripXmlTagsInStrings(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    // 转义字符：连同下一个字符一起原样保留，避免把 \" 误判为字符串结束
                    sb.append(c);
                    if (i + 1 < json.length()) {
                        sb.append(json.charAt(i + 1));
                        i++;
                    }
                } else if (c == '"') {
                    inString = false;
                    sb.append(c);
                } else if (c == '<') {
                    // 尝试匹配 <...> 或 </...> 标签，若是则整段跳过
                    int end = findXmlTagEnd(json, i);
                    if (end > i) {
                        i = end;
                    } else {
                        sb.append(c);
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inString = true;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 从位置 start 判断是否为 XML 标签（{@code <xxx>} 或 {@code </xxx>}）。
     *
     * <p>注意：LLM 返回的 JSON 中 {@code newText} 字段可能包含 Java 源代码，
     * 其中 Java 泛型语法如 {@code List<FeatureEntity>}、{@code Map<String, Object>}
     * 中的 {@code <FeatureEntity>} 会被误判为 XML 标签。为避免误删，增加以下区分规则：</p >
     * <ul>
     *     <li>XML 标签名通常以小写字母开头（如 {@code <div>}、{@code <quick_command>}）</li>
     *     <li>Java 泛型类型名以大写字母开头（如 {@code FeatureEntity}、{@code String}）</li>
     *     <li>当 {@code '<'} 后的标签名以大写字母开头时，视为 Java 泛型而非 XML 标签，返回 -1</li>
     * </ul>
     *
     * @param json  JSON 字符串
     * @param start 起始下标，指向 {@code '<'}
     * @return 若为合法 XML 标签则返回标签结束下标（含 {@code '>'}），否则返回 -1
     */
    private int findXmlTagEnd(String json, int start) {
        if (start >= json.length() || json.charAt(start) != '<') {
            return -1;
        }
        int i = start + 1;
        // 允许闭合标签开头的 '/'
        if (i < json.length() && json.charAt(i) == '/') {
            i++;
        }
        // 标签名：必须以字母开头
        if (i >= json.length() || !Character.isLetter(json.charAt(i))) {
            return -1;
        }
        // Java 泛型类型名以大写字母开头（如 FeatureEntity、String、Object），
        // XML 标签名通常以小写字母开头（如 div、quick_command）。
        // 当 '<' 后跟大写字母时，判定为 Java 泛型而非 XML 标签，避免误删。
        if (Character.isUpperCase(json.charAt(i))) {
            return -1;
        }
        // 标签名可含字母、数字、下划线、连字符，直到遇到 '>'
        while (i < json.length()) {
            char ch = json.charAt(i);
            if (ch == '>') {
                return i;
            }
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                i++;
            } else {
                return -1;
            }
        }
        return -1;
    }


    /**
     * 将 JSON 字符串字面量内部"真实存在的控制字符"转义为对应的 JSON 转义序列。
     *
     * <p>部分模型在生成 JSON 时，会把多行 Java 源码或带缩进的文本以"真实换行符/回车符/制表符"
     * 直接写入字符串值内部（而非写成 {@code \n}、{@code \r}、{@code \t} 转义序列）。
     * 在 JSON 规范中，字符串值内部不允许出现未转义的控制字符（U+0000 ~ U+001F），
     * 否则标准 JSON 解析会抛出 {@code Invalid control character} 异常，导致整个 plan 解析失败。
     * 该方法逐字符扫描并跟踪字符串字面量状态，仅把字符串值内部真实的控制字符转义为对应的
     * JSON 转义序列（{@code \n}、{@code \r}、{@code \t}、{@code \b}、{@code \f} 或
     * {@code \u005CuXXXX}）；对已合法转义的 JSON 无副作用，不影响正常解析。</p >
     *
     * @param json 原始 JSON 字符串
     * @return 控制字符已转义后的 JSON 字符串
     */
    private String escapeControlCharsInStrings(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    // 已转义字符：连同下一个字符一起原样保留，避免把 \n 等误当作真实控制字符
                    sb.append(c);
                    if (i + 1 < json.length()) {
                        sb.append(json.charAt(i + 1));
                        i++;
                    }
                } else if (c == '"') {
                    inString = false;
                    sb.append(c);
                } else if (c < 0x20) {
                    // 字符串值内部的真实控制字符，转义为对应的 JSON 转义序列
                    switch (c) {
                        case '\n':
                            sb.append("\\n");
                            break;
                        case '\r':
                            sb.append("\\r");
                            break;
                        case '\t':
                            sb.append("\\t");
                            break;
                        case '\b':
                            sb.append("\\b");
                            break;
                        case '\f':
                            sb.append("\\f");
                            break;
                        default:
                            sb.append(String.format("\\u%04x", (int) c));
                            break;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inString = true;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 修复 JSON 字符串字面量内部"未正确转义的反斜杠"。
     *
     * <p>部分模型在生成 JSON 时，会把 Windows 路径等以单反斜杠形式直接写入字符串值内部，
     * 例如 {@code "path": "D:\FlinkTest\demo\..."}。在 JSON 规范中，反斜杠必须转义为
     * {@code \\}，否则 {@code \F}、{@code \d} 等会成为非法转义序列，导致标准 JSON 解析失败。
     * 该方法逐字符扫描并跟踪字符串字面量状态，仅把字符串值内部"非法的反斜杠转义"修正为
     * 合法的 {@code \\} 形式；对已合法转义的 JSON（如旧模型输出）无副作用，不影响旧模型解析。</p >
     *
     * @param json 原始 JSON 字符串
     * @return 修复非法反斜杠转义后的 JSON 字符串
     */
    private String fixInvalidBackslashEscapes(String json) {

        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    if (i + 1 < json.length()) {
                        char next = json.charAt(i + 1);
                        if (isValidJsonEscape(next)) {
                            // 合法转义序列（如 \\、\"、\n、\t 等），原样保留
                            sb.append(c);
                            sb.append(next);
                            i++;
                        } else {
                            // 非法转义：模型漏写了一个反斜杠，这里补成 \\ 后再处理下一字符
                            sb.append('\\').append('\\');
                        }
                    } else {
                        // 字符串结尾处孤立的反斜杠，补成 \\
                        sb.append('\\').append('\\');
                    }
                } else if (c == '"') {
                    inString = false;
                    sb.append(c);
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inString = true;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 判断字符是否为 JSON 中合法的反斜杠转义目标字符。
     *
     * @param c 待判断字符
     * @return 若为合法转义目标字符（" \ / b f n r t u）则返回 true
     */
    private boolean isValidJsonEscape(char c) {
        return c == '"' || c == '\\' || c == '/'
                || c == 'b' || c == 'f'
                || c == 'n' || c == 'r' || c == 't'
                || c == 'u';
    }

    /**
     * 判断 JSON 解析异常是否由"步骤对象未闭合"引起。
     *
     * <p>当模型输出遗漏某个步骤对象的闭合 '}' 时，Jackson 通常抛出
     * "Unexpected character ('{')": was expecting double-quote to start field name，
     * 其特征是错误消息中包含 "was expecting double-quote to start field name"。</p >
     *
     * @param e JSON 解析异常
     * @return 若该异常特征符合"对象未闭合导致的字段名预期错误"则返回 true
     */
    private boolean isUnclosedObjectError(JsonParseException e) {
        String msg = e.getMessage();
        return msg != null
                && msg.contains("was expecting double-quote to start field name");
    }

    /**
     * 修复 JSON 中"步骤对象未闭合"导致的结构错误。
     *
     * <p>部分模型在生成 JSON 时可能遗漏某个对象（如计划 steps 数组中的步骤对象）的闭合 '}'，
     * 导致后续的 '{' 被解析器误判为前一个对象的嵌套值，从而抛出
     * "Unexpected character ('{')": was expecting double-quote to start field name。</p >
     *
     * <p>修复思路：逐字符扫描并维护结构深度栈（记录 '{' 与 '[' 的开启位置）。当在对象内部
     * 遇到 '{'，且其前一个非空白字符是 ','（在对象内逗号后应期待字段名而非 '{'），则判定为
     * 前一个对象未闭合，自动在当前位置前补充 '}'。该修复仅作用于结构层（非字符串字面量内部），
     * 对完全合法的 JSON 无副作用，不影响旧模型解析。</p >
     *
     * @param json 原始 JSON 字符串
     * @return 修复未闭合对象后的 JSON 字符串
     */
    private String repairUnclosedObjects(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length() + 8);
        // 结构深度栈：记录每个未闭合的 '{' 或 '[' 字符
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && !isEscaped(json, i)) {
                inString = !inString;
                sb.append(c);
                continue;
            }
            if (inString) {
                sb.append(c);
                continue;
            }
            if (c == '{') {
                // 若当前栈顶是 '{'（处于对象内），且前一个非空白字符是 ','，
                // 说明前一个对象未闭合（对象内逗号后应期待字段名 " 而非 {），补充 '}'。
                // 持续补充直到栈顶不再是未闭合对象。
                if (!stack.isEmpty() && stack.peek() == '{') {
                    int prev = sb.length() - 1;
                    while (prev >= 0 && Character.isWhitespace(sb.charAt(prev))) {
                        prev--;
                    }
                    if (prev >= 0 && sb.charAt(prev) == ',') {
                        while (!stack.isEmpty() && stack.peek() == '{') {
                            sb.append('}');
                            stack.pop();
                        }
                    }
                }
                stack.push('{');
                sb.append(c);
            } else if (c == '[') {
                stack.push('[');
                sb.append(c);
            } else if (c == '}') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                sb.append(c);
            } else if (c == ']') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                sb.append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 判断指定位置的字符是否为被反斜杠转义（即前导反斜杠数量为奇数）。
     *
     * @param json JSON 字符串
     * @param idx  字符位置
     * @return 若该字符被转义则返回 true
     */
    private boolean isEscaped(String json, int idx) {
        int count = 0;
        for (int i = idx - 1; i >= 0 && json.charAt(i) == '\\'; i--) {
            count++;
        }
        return (count % 2) == 1;
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

    /**
     * 确保 Skill 要求的参考模板已被读取。
     *
     * <p>当 CODING 阶段但 Skill 的 rules / implementation 中明确要求读取的参考模板
     * （docs/ 目录下的 .md 文件）尚未在 OBSERVATIONS 中被成功读取过时，自动在计划最前面
     * 插入 read_file 步骤读取该模板，确保模板内容进入后续 OBSERVATIONS 上下文。</p >
     *
     * <p>触发条件特意【不要求计划包含写入操作】。若 LLM 因尚未看到模板而反复停留在
     * search_code / read_file 定位目标类（纯只读计划），此时计划不含写入，若仍要求
     * "先有写入才插入模板读取"会形成死循环——LLM 看不到模板就无从生成符合模板的写入代码，
     * 于是永远不写代码、模板也永远进不了上下文。因此在 CODING 阶段只要匹配到带 docs/ 模板
     * 的 Skill 且模板未读过，就先强制读模板，尽早把模板喂给 LLM，打破死循环。</p >
     *
     * @param context 上下文
     * @param plan    原始计划
     * @return 修复后的计划（若需要插入模板读取步骤则返回新计划，否则返回原计划）
     */
    private CodePlan ensureSkillTemplateRead(AgentContext context, CodePlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return plan;
        }
        // 仅在 CODING 阶段处理
        if (context.getPhase() != AgentPhase.CODING) {
            return plan;
        }

        // 获取匹配的 Skill 列表
        List<Skill> skills = skillMatcher.match(context.getTask());
        if (skills == null || skills.isEmpty()) {
            return plan;
        }

        // 从所有匹配的 Skill 中提取需要读取的模板路径
        Set<String> templatePaths = extractSkillTemplatePaths(skills);
        if (templatePaths.isEmpty()) {
            return plan;
        }

        // 过滤出尚未读取过的模板
        // 注意：必须用完整的 context.getObservations() 而非 getPlannerObservations()，
        // 因为 getPlannerObservations 只保留最近 3 条，之前读过的模板会被截断，
        // 导致 hasReadReferenceFile 找不到已读记录而重复插入 read_file 步骤。
        List<Observation> observations = context.getObservations();
        if (observations == null) {
            observations = new ArrayList<>();
        }
        List<String> unreadPaths = new ArrayList<>();
        for (String path : templatePaths) {
            if (!hasReadReferenceFile(path, observations)) {
                unreadPaths.add(path);
            }
        }
        // 分块模板（docs/predict-call-template-parts/ 下）逐轮插入，每轮只插入一个未读分块。
        // 之前只插入总览分块、指望 LLM 自行 read_file 其余分块，但 LLM 一旦生成多个
        // read_file（含模板分块）就会被 forced rewrite 当作"无效纯只读计划"缩减掉，
        // 导致 01~05 分块永远不被读取，生成的代码完全不参考模板。
        // 现在改为每轮插入一个未读分块（优先总览），确保所有分块最终都能被读取，
        // 同时避免一次性插入全部分块导致 prompt 暴涨 504 超时。
        List<String> filteredPaths = new ArrayList<>();
        String firstUnreadChunk = null;
        for (String path : unreadPaths) {
            if (path != null && path.contains("predict-call-template-parts/")) {
                // 分块模板：优先插入总览，否则取第一个未读分块
                if (path.contains("00-overview-and-constraints")) {
                    filteredPaths.add(path);
                    break;
                }
                if (firstUnreadChunk == null) {
                    firstUnreadChunk = path;
                }
            } else {
                // 非分块模板（如整份模板或其他 docs/*.md）照常插入
                filteredPaths.add(path);
            }
        }
        // 如果总览已读过但还有未读分块，插入下一个未读分块
        if (filteredPaths.isEmpty() && firstUnreadChunk != null) {
            filteredPaths.add(firstUnreadChunk);
        }
        unreadPaths = filteredPaths;

        if (unreadPaths.isEmpty()) {
            // 所有模板都已读取过，无需插入
            return plan;
        }

        log.warn("skill template not read before write, inserting read_file steps for templates={}", unreadPaths);

        // 在计划最前面插入 read_file 步骤读取尚未读取的模板
        List<PlanStep> newSteps = new ArrayList<>();
        int order = 1;
        for (String path : unreadPaths) {
            newSteps.add(buildTemplateReadStep(order++, path));
        }
        // 追加原有步骤，重新编号
        for (PlanStep originalStep : plan.getSteps()) {
            newSteps.add(reorderStep(order++, originalStep));
        }

        return CodePlan.builder()
                .task(plan.getTask())
                .steps(newSteps)
                .build();
    }

    /**
     * 判断计划中是否包含写入操作（write_file / patch_file / create_file）。
     *
     * @param plan 计划
     * @return 若包含至少一个写入操作则返回 true
     */
    private boolean hasWriteAction(CodePlan plan) {
        if (plan == null || plan.getSteps() == null) {
            return false;
        }
        for (PlanStep step : plan.getSteps()) {
            String tool = step.getTool();
            if ("write_file".equals(tool)
                    || "patch_file".equals(tool)
                    || "create_file".equals(tool)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从匹配的 Skill 列表中提取需要读取的参考模板路径。
     *
     * <p>扫描每个 Skill 的 rules、implementation 和 template 字段，
     * 使用正则表达式提取形如 {@code docs/xxx.md} 的路径，
     * 兼容路径前后可能存在的引号、反引号等包裹字符。</p >
     *
     * @param skills 匹配的 Skill 列表
     * @return 需要读取的模板路径集合（去重）
     */
    private Set<String> extractSkillTemplatePaths(List<Skill> skills) {
        Set<String> paths = new LinkedHashSet<>();
        if (skills == null || skills.isEmpty()) {
            return paths;
        }

        // 匹配 docs/ 目录下的 .md 文件路径，兼容以下形式：
        // docs/predict-call-template.md
        // `docs/predict-call-template.md`
        // "docs/predict-call-template.md"
        // read_file docs/predict-call-template.md
        Pattern pattern = Pattern.compile("[`'\"]?(docs/[\\w./-]+\\.md)[`'\"]?");

        for (Skill skill : skills) {
            // 从 rules 中提取
            if (skill.getRules() != null) {
                for (String rule : skill.getRules()) {
                    extractPathsFromText(rule, pattern, paths);
                }
            }
            // 从 implementation 中提取
            if (skill.getImplementation() != null) {
                for (String impl : skill.getImplementation()) {
                    extractPathsFromText(impl, pattern, paths);
                }
            }
            // 从 template 字段中提取
            if (skill.getTemplate() != null && !skill.getTemplate().isEmpty()) {
                extractPathsFromText(skill.getTemplate(), pattern, paths);
            }
        }

        return paths;
    }

    /**
     * 从文本中提取 docs/ 目录下的 .md 文件路径并添加到结果集合中。
     *
     * @param text    待扫描的文本
     * @param pattern 匹配模板路径的正则表达式
     * @param paths   提取结果集合
     */
    private void extractPathsFromText(String text, Pattern pattern, Set<String> paths) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String path = matcher.group(1);
            if (path != null && !path.isEmpty()) {
                paths.add(path);
            }
        }
    }

    /**
     * 构建读取参考模板的 read_file 步骤。
     *
     * @param order 步骤编号
     * @param path  模板文件路径
     * @return read_file 步骤
     */
    private PlanStep buildTemplateReadStep(int order, String path) {
        return PlanStep.builder()
                .order(order)
                .tool("read_file")
                .input(ToolInput.builder()
                        .path(path)
                        .startLine(1)
                        .endLine(1000)
                        .build())
                .description("Read skill reference template before coding: " + path)
                .build();
    }

    /**
     * 对已有步骤重新编号。
     *
     * @param order 新的步骤编号
     * @param step  原始步骤
     * @return 重新编号后的步骤
     */
    private PlanStep reorderStep(int order, PlanStep step) {
        return com.cmbchina.llm.planner.PlanStep.builder()
                .order(order)
                .tool(step.getTool())
                .input(step.getInput())
                .description(step.getDescription())
                .build();
    }
}
