package com.cmbchina.llm.runtime;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import com.cmbchina.llm.agent.AgentStatus;
import com.cmbchina.llm.agent.CodingTrack;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.agent.phase.PhaseManager;
import com.cmbchina.llm.planner.CodePlan;
import com.cmbchina.llm.planner.PlanStep;
import com.cmbchina.llm.planner.Planner;
import com.cmbchina.llm.planner.SkillTemplateCoordinator;
import com.cmbchina.llm.planner.S2TestCoordinator;
import com.cmbchina.llm.project.ProjectIndex;
import com.cmbchina.llm.project.ProjectIndexer;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.verify.VerifyEngine;
import com.cmbchina.llm.verify.VerifyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.stream.Collectors;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private static final int MAX_ITERATION = 80;
    private static final int MAX_READ_ONLY_ROUNDS = 6;

    private final Planner planner;
    private final ToolExecutor toolExecutor;
    private final ProjectIndexer projectIndexer;
    private final PhaseManager phaseManager;
    private final VerifyEngine verifyEngine;
    private final SkillTemplateCoordinator skillTemplateCoordinator;
    private final S2TestCoordinator testCoordinator;

    @Value("${codex.agent.test-generation-enabled:false}")
    private boolean testGenerationEnabled;

    @Value("${codex.agent.max-repair-rounds:5}")
    private int maxRepairRounds;

    @javax.annotation.PostConstruct
    public void validateConfiguration() {
        if (maxRepairRounds < 0 || maxRepairRounds > 10) {
            throw new IllegalArgumentException("codex.agent.max-repair-rounds 必须为 0～10");
        }
    }

    public void run(AgentContext context) {
        log.info("agent loop start agentId={} task={} s2Enabled={}",
                context.getAgentId(), context.getTask(), testGenerationEnabled);
        context.setStatus(AgentStatus.ANALYZING);
        Set<String> executedPlans = new HashSet<>();
        Map<String, Integer> failureCounts = new HashMap<>();
        int readOnlyRounds = 0;
        int duplicateRounds = 0;
        int planningFailures = 0;
        int emptyRounds = 0;
        int repairRounds = 0;
        boolean codingS1Done = false;
        boolean codingS2Done = !testGenerationEnabled;

        for (int i = 0; i < MAX_ITERATION; i++) {
            if (context.getPhase() == AgentPhase.FINISH) {
                return;
            }
            if (context.getPhase() == AgentPhase.CODING) {
                context.setCodingTrack(codingS1Done && !codingS2Done
                        ? CodingTrack.S2_TEST : CodingTrack.S1_BUSINESS);
                if (testGenerationEnabled && context.getCodingTrack() == CodingTrack.S2_TEST) {
                    try {
                        testCoordinator.initialize(context);
                        int completed = testCoordinator.completedCount(context);
                        codingS2Done = testCoordinator.advance(context);
                        if (testCoordinator.completedCount(context) > completed) {
                            readOnlyRounds = 0;
                            duplicateRounds = 0;
                            executedPlans.clear();
                        }
                        if (codingS2Done) {
                            context.setPhase(AgentPhase.VERIFY);
                            continue;
                        }
                    } catch (RuntimeException exception) {
                        stop(context, "S2 清单或源码异常: " + exception.getMessage());
                        return;
                    }
                }
            }
            if (context.getPhase() == AgentPhase.VERIFY) {
                if (!codingS1Done || !codingS2Done) {
                    context.setPhase(AgentPhase.CODING);
                    continue;
                }
                if (handleVerifyPhase(context)) {
                    return;
                }
                int limit = testGenerationEnabled ? maxRepairRounds : 2;
                if (++repairRounds > limit) {
                    stop(context, "验证修复达到上限 " + limit + "，保留错误供人工检查: " + context.getVerifyErrors());
                    return;
                }
                log.info("repair round={}/{} modelCalls={}", repairRounds, limit, context.getModelCalls());
                continue;
            }

            CodePlan plan;
            boolean recoveryRead;
            try {
                plan = planner.createPlan(context);
                recoveryRead = Boolean.TRUE.equals(context.getVariables().remove("patch_preflight_recovery_plan"));
                if (!recoveryRead) {
                    planningFailures = 0;
                }
            } catch (Exception e) {
                // HTTP/额度/鉴权/网络错误不由外层重试，避免与客户端重试相乘。
                if (isModelFailure(e) || ++planningFailures >= 3
                        || e.getMessage() != null && e.getMessage().startsWith("规划模型调用已达到任务预算")) {
                    stop(context, "规划失败，停止模型调用: " + failureMessage(e));
                    return;
                }
                if (context.getPhase() != AgentPhase.REPAIR) {
                    context.getVerifyErrors().clear();
                }
                context.getVerifyErrors().removeIf(error -> error.startsWith("上一计划被拒绝"));
                context.getVerifyErrors().add("上一计划被拒绝，请修正后重试: " + e.getMessage());
                log.warn("plan rejected attempt={} error={}", planningFailures, e.getMessage());
                continue;
            }
            if (isEmptyPlan(plan)) {
                if (context.getPhase() == AgentPhase.REPAIR
                        || context.getPhase() == AgentPhase.CODING && context.getCodingTrack() == CodingTrack.S2_TEST) {
                    if (++emptyRounds >= 2) {
                        stop(context, "清单或验证尚未通过却连续返回空计划: " + context.getVerifyErrors());
                        return;
                    }
                    context.getVerifyErrors().add("空计划不能替代测试清单完成或修复，请处理实际未满足项");
                    continue;
                }
                if (context.getPhase() == AgentPhase.CODING
                        && skillTemplateCoordinator.currentTemplate(context) != null) {
                    if (++emptyRounds >= 2) {
                        stop(context, "S1 当前模板尚未完成却连续返回空计划: "
                                + skillTemplateCoordinator.currentTemplate(context) + " " + context.getTemplateErrors());
                        return;
                    }
                    context.getVerifyErrors().add("当前模板全部产物尚未完成，不允许用空计划结束: "
                            + context.getTemplateErrors());
                    continue;
                }
                if (hasSuccessfulCodeChange(context) && context.getPhase() != AgentPhase.ANALYSIS) {
                    codingS1Done = true;
                    context.setPhase(AgentPhase.VERIFY);
                    continue;
                }
                stop(context, "任务尚无成功代码修改，模型返回空计划");
                return;
            }
            emptyRounds = 0;
            // 忽略模型自然语言描述，按工具与参数判断相同计划；只在真实写入后清空集合。
            String planKey = plan.getSteps().stream()
                    .map(step -> step.getTool() + ":" + step.getInput()).collect(Collectors.joining("|"));
            // 仅服务端安排的一次补丁恢复读取允许重复；不重置此前规划失败计数。
            if (!executedPlans.add(planKey) && !recoveryRead) {
                if (context.getPhase() != AgentPhase.REPAIR) {
                    context.getVerifyErrors().clear();
                }
                context.getVerifyErrors().add("检测到重复计划，请使用已有观察完成当前产物，禁止重复读取相同内容。");
                log.warn("duplicate plan agentId={} count={} signature={}",
                        context.getAgentId(), duplicateRounds + 1, Integer.toHexString(planKey.hashCode()));
                if (++duplicateRounds >= 2) {
                    stop(context, "连续重复计划，停止以避免空耗模型额度；当前模板="
                            + skillTemplateCoordinator.currentTemplate(context));
                    return;
                }
                continue;
            }
            if ((context.getPhase() == AgentPhase.CODING || context.getPhase() == AgentPhase.REPAIR)
                    && isPureReadPlan(plan) && !isSkillTemplateReadPlan(plan)) {
                if (++readOnlyRounds > MAX_READ_ONLY_ROUNDS) {
                    stop(context, "连续只读未产生代码变更，停止模型调用；当前模板="
                            + skillTemplateCoordinator.currentTemplate(context));
                    return;
                }
                if (readOnlyRounds >= 3 && context.getPhase() == AgentPhase.CODING
                        && context.getCodingTrack() == CodingTrack.S1_BUSINESS
                        && skillTemplateCoordinator.currentTemplate(context) != null) {
                    context.getVerifyErrors().removeIf(error -> error.startsWith("连续只读提醒:"));
                    context.getVerifyErrors().add("连续只读提醒: 已连续 " + readOnlyRounds + "/"
                            + MAX_READ_ONLY_ROUNDS + " 轮未修改代码，当前模板="
                            + skillTemplateCoordinator.currentTemplate(context)
                            + "。请复用已固定的真实依赖源码和搜索结果，补齐当前模板产物；"
                            + "标准产物路径已确认不存在且搜索无等价实现时可按模板新建，不要重复搜索同一类型。"
                            + "依赖包中的公共类在源码搜索不到不代表缺依赖；按模板 import 和已有依赖依据处理，"
                            + "确有未知签名或依赖信息时明确指出，不猜测。只读上限保持不变。");
                    log.warn("S1 read-only progress warning rounds={}/{} template={}", readOnlyRounds,
                            MAX_READ_ONLY_ROUNDS, skillTemplateCoordinator.currentTemplate(context));
                }
            }

            List<ToolCallResult> results = toolExecutor.execute(plan, context);
            if (shouldStopRepeatedFailure(results, failureCounts)) {
                stop(context, "相同工具错误重复发生: " + failureSignature(results));
                return;
            }
            boolean wrote = context.getLastObservations().stream()
                    .anyMatch(observation -> observation.isSuccess()
                            && isChangeTool(observation.getTool()));
            if (wrote) {
                readOnlyRounds = 0;
                duplicateRounds = 0;
                executedPlans.clear();
                context.getVerifyErrors().removeIf(error -> error.startsWith("连续只读提醒:"));
                if (context.getPhase() == AgentPhase.CODING) {
                    context.getVerifyErrors().clear();
                }
            }
            context.setProjectIndex(projectIndexer.build(context.getObservations()));
            AgentPhase current = context.getPhase();
            if (current == AgentPhase.CODING && context.getCodingTrack() == CodingTrack.S2_TEST) {
                // 清单由下一轮读取最新落盘结果后推进，单次写测试不等于 S2 完成。
                continue;
            }
            if (current == AgentPhase.CODING && context.getCodingTrack() == CodingTrack.S1_BUSINESS
                    && skillTemplateCoordinator.hasTemplates(context)) {
                int completedBefore = context.getCompletedTemplates().size();
                skillTemplateCoordinator.advanceAfterExecution(context);
                if (context.getCompletedTemplates().size() > completedBefore) {
                    // 模板切换是实际进展，新阶段可重新读取它需要的现有源码。
                    readOnlyRounds = 0;
                    duplicateRounds = 0;
                    executedPlans.clear();
                    context.getVerifyErrors().removeIf(error -> error.startsWith("连续只读提醒:"));
                }
                codingS1Done = skillTemplateCoordinator.currentTemplate(context) == null;
                // 不能以一次成功写入代替整个 S1 完成，模板产物齐全才允许进入验证。
                context.setPhase(codingS1Done && codingS2Done ? AgentPhase.VERIFY : AgentPhase.CODING);
                continue;
            }
            if (current == AgentPhase.REPAIR && !wrote) {
                // 只读不能触发 REPAIR -> VERIFY，避免读取/验证之间空转。
                continue;
            }
            AgentPhase next = phaseManager.next(current, context);
            if (current == AgentPhase.CODING && next == AgentPhase.VERIFY) {
                if (context.getCodingTrack() == CodingTrack.S1_BUSINESS) {
                    codingS1Done = hasWrittenMainCode(context, results);
                } else {
                    codingS2Done = hasWrittenTestCode(context, results);
                }
                if (!codingS1Done || !codingS2Done) {
                    next = AgentPhase.CODING;
                }
            }
            log.info("phase change {} -> {} modelCalls={}", current, next, context.getModelCalls());
            context.setPhase(next);
        }
        stop(context, "任务达到最大迭代轮数，尚未通过验证");
    }

    private boolean isChangeTool(String tool) {
        return "write_file".equals(tool) || "patch_file".equals(tool)
                || "create_file".equals(tool) || "edit_file".equals(tool);
    }

    private boolean isModelFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.web.client.RestClientException
                    || "call qwen failed".equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private String failureMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof org.springframework.web.client.HttpStatusCodeException) {
            // 不把服务端原始响应（可能包含请求信息）直接塞回下一次模型提示词。
            return "模型 HTTP " + ((org.springframework.web.client.HttpStatusCodeException) cause).getRawStatusCode()
                    + "，请检查额度、鉴权或模型配置";
        }
        // 业务预检外层携带路径、步骤和真实片段，不能只显示最底层的笼统匹配失败。
        return isModelFailure(error) ? cause.getMessage() : error.getMessage();
    }

    private void stop(AgentContext context, String reason) {
        context.getVerifyErrors().clear();
        context.getVerifyErrors().add(reason);
        context.setPhase(AgentPhase.FINISH);
        log.warn("agent stopped agentId={} modelCalls={} reason={}",
                context.getAgentId(), context.getModelCalls(), reason);
    }

    /**
     * 判断最近一次执行结果中是否有写入 src/main 目录的成功代码变更。
     *
     * <p>用于 CODING 双子行程识别 S1（业务代码）是否已落盘。路径来源取工具返回值中的
     * 文件路径（如 FileContent / ToolInput），粗略按路径是否包含 "/main/" 目录判定。</p >
     *
     * @param context Agent 上下文（用于读取最近观察）
     * @param results 最近一次执行的工具调用结果
     * @return 本轮是否已写入了 src/main 业务代码
     */
    private boolean hasWrittenMainCode(AgentContext context, List<ToolCallResult> results) {
        return hasWrittenUnder(context, results, "/main/");
    }

    /**
     * 判断最近一次执行结果中是否有写入 src/test 目录的成功代码变更。
     *
     * <p>用于 CODING 双子行程识别 S2（测试类）是否已落盘。路径来源取工具返回值中的
     * 文件路径（如 FileContent / ToolInput），粗略按路径是否包含 "/test/" 目录判定。</p >
     *
     * @param context Agent 上下文（用于读取最近观察）
     * @param results 最近一次执行的工具调用结果
     * @return 本轮是否已写入了 src/test 测试代码
     */
    private boolean hasWrittenTestCode(AgentContext context, List<ToolCallResult> results) {
        return hasWrittenUnder(context, results, "/test/");
    }

    /**
     * 根据目标目录片段（如 "/main/"、"/test/"）判定最近执行中是否存在成功写入该目录的文件变更。
     *
     * @param context Agent 上下文
     * @param results 最近一次执行的工具调用结果
     * @param dirFragment 目录片段，用于区分 src/main 与 src/test
     * @return 是否存在成功写入该目录的文件变更
     */
    private boolean hasWrittenUnder(AgentContext context, List<ToolCallResult> results, String dirFragment) {
        // 优先从最近的工具调用结果中解析路径。
        if (results != null) {
            for (ToolCallResult r : results) {
                if (r == null || !r.isSuccess()) {
                    continue;
                }
                String tool = r.getTool();
                if ("patch_file".equals(tool) || "write_file".equals(tool) || "create_file".equals(tool)) {
                    String p = extractPathFromResult(r.getResult());
                    if (p != null && p.replace('\\', '/').contains(dirFragment)) {
                        return true;
                    }
                }
            }
        }
        // 兜底：从最近观察记录中解析路径。
        if (context.getObservations() != null) {
            for (Observation o : context.getObservations()) {
                if (o == null || o.getTool() == null || !o.isSuccess()) {
                    continue;
                }
                String tool = o.getTool();
                if ("patch_file".equals(tool) || "write_file".equals(tool) || "create_file".equals(tool)) {
                    String p = extractPathFromResult(o.getResult());
                    if (p == null) {
                        p = extractPathFromInput(o.getInput());
                    }
                    if (p != null && p.replace('\\', '/').contains(dirFragment)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    /**
     * 从工具返回值对象中提取文件路径。
     *
     * <p>write_file / create_file / patch_file 的成功返回值通常为 FileContent，内含 path 字段；
     * 若为 {@link java.io.File} 或字符串，则兼容提取。无法解析时返回 {@code null}。</p >
     *
     * @param result 工具返回值
     * @return 文件路径；解析不到时返回 {@code null}
     */
    private String extractPathFromResult(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof FileContent) {
            return ((FileContent) result).getPath();
        }
        if (result instanceof java.io.File) {
            return ((java.io.File) result).getPath();
        }
        if (result instanceof String) {
            return (String) result;
        }
        return null;
    }

    /**
     * 从工具输入对象中提取文件路径（patch_file / write_file 等入参通常为 ToolInput）。
     *
     * @param input 工具输入对象
     * @return 文件路径；解析不到时返回 {@code null}
     */
    private String extractPathFromInput(Object input) {
        if (input instanceof ToolInput) {
            ToolInput in = (ToolInput) input;
            if (in.getPath() != null) {
                return in.getPath();
            }
        }
        return null;
    }

    /**
     * 判断计划是否为纯只读计划（所有步骤都是 read_file / search_code / search 等只读工具）。
     *
     * @param plan 代码计划
     * @return 是否纯只读
     */
    private boolean isPureReadPlan(CodePlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return false;
        }
        for (PlanStep step : plan.getSteps()) {
            if (step == null || step.getTool() == null) {
                continue;
            }
            String tool = step.getTool();
            boolean readOnly = "read_file".equals(tool)
                    || "search_code".equals(tool)
                    || "search".equals(tool)
                    || "list_files".equals(tool);
            if (!readOnly) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断计划是否仅读取 Skill 参考模板。
     *
     * <p>模板按分块逐轮读取是生成前的必要前置步骤，不能被误判为无效只读循环。</p>
     *
     * @param plan 待判断计划
     * @return 仅包含 docs 下 Markdown 模板读取时返回 true
     */
    private boolean isSkillTemplateReadPlan(CodePlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return false;
        }
        for (PlanStep step : plan.getSteps()) {
            if (step == null || !"read_file".equals(step.getTool())
                    || !(step.getInput() instanceof ToolInput)) {
                return false;
            }
            String path = ((ToolInput) step.getInput()).getPath();
            if (path == null) {
                return false;
            }
            String normalized = path.replace('\\', '/').toLowerCase();
            if (!normalized.startsWith("docs/") || !normalized.endsWith(".md")) {
                return false;
            }
        }
        return true;
    }
    /**
     * 检查 Agent 执行历史中是否已有成功的代码修改动作。
     *
     * <p>遍历所有 Observation，判断是否存在 patch_file / write_file / create_file
     * 工具成功执行的记录。当 REPAIR 阶段反复只读时，如果已有成功修改，
     * 说明核心改造任务已完成，可直接进入 FINISH 终止，避免无限循环耗尽迭代。</p >
     *
     * @param context Agent 上下文
     * @return 是否已有成功的代码修改
     */
    private boolean hasSuccessfulCodeChange(AgentContext context) {
        if (context.getObservations() == null || context.getObservations().isEmpty()) {
            return false;
        }
        for (Observation o : context.getObservations()) {
            if (o == null || o.getTool() == null || !o.isSuccess()) {
                continue;
            }
            String tool = o.getTool();
            if ("patch_file".equals(tool)
                    || "write_file".equals(tool)
                    || "create_file".equals(tool)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 处理 VERIFY 阶段。
     *
     * <p>VerifyEngine 内部按两层执行：静态代码检查通过后调用 run_test 执行测试。
     * 测试失败时（编译错误 / 断言失败 / 异常堆栈等），将解析出的失败信息连同
     * mvn 输出摘要一起注入 verifyErrors，供 REPAIR 阶段 LLM 定位修复。</p >
     *
     * @param context Agent 上下文
     * @return true 表示验证通过、任务完成，false 表示进入 REPAIR
     */
    private boolean handleVerifyPhase(AgentContext context) {
        List<String> contractErrors = skillTemplateCoordinator.validateAll(context);
        VerifyResult verifyResult = contractErrors.isEmpty()
                ? verifyEngine.verify(context) : VerifyResult.failed(contractErrors);
        context.getVariables().put("verify_result", verifyResult);
        if (verifyResult.isSuccess()) {
            context.getVerifyErrors().clear();
            context.setPhase(AgentPhase.FINISH);
            return true;
        }
        context.setPhase(AgentPhase.REPAIR);
        context.getVerifyErrors().clear();
        context.getVerifyErrors().addAll(verifyResult.getErrors());
        if (verifyResult.isBlocked()) {
            stop(context, "测试环境阻塞，停止自动修复: " + context.getVerifyErrors());
            return true;
        }
        if (testGenerationEnabled) {
            try {
                String signature = testCoordinator.fingerprint(context) + ":" + context.getVerifyErrors();
                if (signature.equals(context.getVariables().put("s2_last_failure", signature))) {
                    stop(context, "同一错误重复且代码无实质变化，停止自动修复: " + context.getVerifyErrors());
                    return true;
                }
            } catch (RuntimeException exception) {
                stop(context, "修复状态读取异常: " + exception.getMessage());
                return true;
            }
        }

        // 测试执行过且失败：补充 mvn 输出末尾摘要，
        // 帮助 REPAIR 阶段 LLM 看到具体编译错误 / 断言失败位置。
        if (verifyResult.isTestExecuted() && !verifyResult.isTestPassed()) {
            String output = verifyResult.getTestOutput();
            if (output != null && !output.isEmpty()) {
                String summary = output.length() > 5000
                        ? output.substring(output.length() - 5000)
                        : output;
                context.getVerifyErrors().add("[mvn test 输出摘要]\n" + summary);
            }
        }

        log.warn("verify failed errors={} testExecuted={} testPassed={}",
                verifyResult.getErrors(), verifyResult.isTestExecuted(), verifyResult.isTestPassed());
        return false;
    }

    private boolean isEmptyPlan(CodePlan plan) {
        return plan == null || plan.getSteps() == null || plan.getSteps().isEmpty();
    }
    private void handleEmptyPlan(AgentContext context) {
        log.warn("empty plan phase={}", context.getPhase());
        if (context.getPhase() == AgentPhase.CODING) {
            context.setPhase(AgentPhase.FINISH);
            return;
        }
        AgentPhase next = phaseManager.next(context.getPhase(), context);
        context.setPhase(next);
    }

    private boolean shouldStopRepeatedFailure(
            List<ToolCallResult> results,
            Map<String, Integer> failureCounts
    ) {
        String signature = failureSignature(results);
        if (signature == null) {
            return false;
        }
        Integer count = failureCounts.get(signature);
        if (count == null) {
            count = 0;
        }
        count++;
        failureCounts.put(signature, count);
        return count >= 2;
    }

    private String failureSignature(List<ToolCallResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ToolCallResult result : results) {
            if (result.isSuccess()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("|");
            }
            sb.append(result.getTool())
                    .append(":")
                    .append(result.getError());
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
