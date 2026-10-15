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
import com.cmbchina.llm.project.ProjectIndex;
import com.cmbchina.llm.project.ProjectIndexer;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.ToolInput;
import com.cmbchina.llm.verify.VerifyEngine;
import com.cmbchina.llm.verify.VerifyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    /**
     * Agent 最大迭代轮次。
     *
     * <p>异步执行场景下不再有网关超时压力，完整流程需要经历 ANALYSIS（1-2轮）、
     * CODING（3-5轮，含读取目标文件/模板/配置+生成代码）、VERIFY（1轮）、
     * REPAIR（2-5轮，修复验证错误+重新生成patch），合计可能需要 10-15 轮。</p >
     *
     * <p>CODING 双子行程改造后，业务代码（S1）与测试类（S2）分两次生成，CODING 阶段
     * 需要在 S1 落盘业务代码后再读取业务类签名并独立生成测试类，整体迭代轮次增加。
     * 此外，LLM 偶发 504 超时后 AgentLoop 异常容错会跳过本轮继续下一轮，也会消耗轮次；
     * patch_file 编造路径被 file protection 阻断后需要重新读取真实路径再写入，也会额外消耗轮次。
     * 因此将上限从 30 提高到 50，确保 S1、S2 及后续 REPAIR 修正都有充足的迭代机会。</p >
     *
     * <p>实际运行中 LLM 分轮读取模板、生成代码、修复 patch 仍频繁耗尽 50 轮，
     * 因此进一步放开到 100，配合只读循环 / 重复计划等兜底机制防止真正意义的死循环。</p >
     */
    private static final int MAX_ITERATION = 100;

    /**
     * 连续纯只读动作轮次上限，超过该上限说明 Agent 陷入"只读不写"死循环，
     * 需要强制注入引导信息并进入 REPAIR，避免空耗迭代轮次。
     *
     * <p>异步执行场景下不再有网关超时压力，CODING 阶段正常流程需要先读取目标文件、
     * 模板文件、配置文件等（可能需要 3-4 轮只读）。</p >
     *
     * <p>CODING 双子行程改造后，S2 生成测试类前还需读取业务类真实签名、pom 依赖等，
     * 前置只读轮次进一步增加；因此将上限从 4 提高到 6，
     * 避免在 LLM 正常读取前置文件时被过早强制进入 REPAIR。</p >
     */
    private static final int MAX_READ_ONLY_ROUNDS = 6;

    private final Planner planner;
    private final ToolExecutor toolExecutor;
    private final ProjectIndexer projectIndexer;
    private final PhaseManager phaseManager;
    private final VerifyEngine verifyEngine;

    public void run(AgentContext context) {
        log.info("agent loop start task={}", context.getTask());
        context.setStatus(AgentStatus.ANALYZING);

        Set<String> executedPlans = new HashSet<>();
        Map<String, Integer> failureCounts = new HashMap<>();
        // 记录连续纯只读计划的轮数
        int readOnlyRounds = 0;
        // ===== CODING 双子行程（S1 业务代码 / S2 测试类）状态 =====
        // 业务代码生成与测试类生成分离（两次 QwenPlanner 调用），同一 AgentLoop 业务链内驱动：
        //   - S1 未完成：codingTrack=S1_BUSINESS，只生成 src/main 业务代码；
        //   - S1 完成、S2 未完成：codingTrack=S2_TEST，只生成 src/test 测试类；
        //   - S2 也完成：才允许转入 VERIFY 执行整体验证（编译 + 测试类落盘）。
        boolean codingS1Done = false;
        boolean codingS2Done = false;

        for (int i = 0; i < MAX_ITERATION; i++) {
            // 根据 CODING 双子行程进度动态设置 codingTrack，供 QwenPlanner 构建对应子行程 Prompt。
            if (context.getPhase() == AgentPhase.CODING) {
                if (codingS1Done && !codingS2Done) {
                    context.setCodingTrack(CodingTrack.S2_TEST);
                } else {
                    context.setCodingTrack(CodingTrack.S1_BUSINESS);
                }
            }
            // 若前一轮已是 S1/S2 完成的 CODING 且被判定为应进入 VERIFY，此处放行双行程前置判断。
            if (context.getPhase() == AgentPhase.FINISH) {
                return;
            }
            if (context.getPhase() == AgentPhase.VERIFY) {
                if (handleVerifyPhase(context)) {
                    return;
                }
                readOnlyRounds = 0;
                continue;
            }

            CodePlan plan;
            try {
                plan = planner.createPlan(context);
            } catch (Exception e) {
                // 模型调用失败（如网关 504 超时、网络抖动等）不中断整个任务，
                // 跳过本轮继续下一轮迭代，让模型有机会重新生成。
                log.warn("createPlan failed, skip this round and retry next iteration: {}", e.getMessage());
                continue;
            }
            if (isEmptyPlan(plan)) {
                handleEmptyPlan(context);
                if (context.getPhase() == AgentPhase.FINISH) {
                    return;
                }
                if (context.getPhase() != AgentPhase.CODING) {
                    readOnlyRounds = 0;
                }
                continue;
            }
            String planKey = plan.toString();

            // ===== 纯只读死循环兜底 =====
            // 在 CODING、REPAIR 两个阶段均统计连续纯只读计划轮数，杜绝"只读不写"的空耗死循环：
            //   - CODING 阶段连续 MAX_READ_ONLY_ROUNDS 轮仍只生成只读动作，注入引导并强制进入 REPAIR；
            //   - REPAIR 阶段同样统计，若 REPAIR 反复只读（说明 LLM 已无法生成写入动作），
            //     为避免榨干 MAX_ITERATION 后无产出退出，直接终止循环标记 FINISH。
            if ((context.getPhase() == AgentPhase.CODING
                    || context.getPhase() == AgentPhase.REPAIR)
                    && isPureReadPlan(plan)) {
                readOnlyRounds++;
            } else {
                readOnlyRounds = 0;
            }
            if (readOnlyRounds >= MAX_READ_ONLY_ROUNDS) {
                // CODING 阶段反复只读时，优先检查是否已有成功的代码修改
                // （patch_file/write_file/create_file）。如果已有成功修改，说明核心改造任务
                // 已完成，可以进入 VERIFY 重新验证。
                //
                // 注意：CODING 双子行程（S1 业务代码 / S2 测试类）下，历史 OBSERVATIONS 中
                // 存在某次成功写入（hasSuccessfulCodeChange 全历史扫描）不能代表本任务业务代码
                // 已完成——S1 尚未落盘时业务代码根本没生成，若此时 force FINISH 会导致模板未读、
                // 业务代码未生成、S2 测试类从未执行。因此 CODING 阶段只有在 S1、S2 都完成时才允许
                // 因只读循环进入 VERIFY；S1 未完成时应引导进入 REPAIR 强制生成业务代码。
                //
                // REPAIR 阶段反复只读时：不再直接 FINISH，而是回到 VERIFY 重新验证。
                // 如果已有成功代码修改，VERIFY 会重新执行编译+测试；如果验证通过则自然 FINISH，
                // 如果验证失败则再次进入 REPAIR，形成真正的"测试-修复-重新测试"闭环。
                // 只有 VERIFY 通过后才结束任务，确保生成的代码是正确的。
                if (context.getPhase() == AgentPhase.REPAIR && hasSuccessfulCodeChange(context)) {
                    log.warn(
                            "read-only loop ({} rounds) in REPAIR but code changes already exist,"
                                    + " go to VERIFY to re-check. planKey={}",
                            readOnlyRounds, planKey);
                    context.setPhase(AgentPhase.VERIFY);
                    readOnlyRounds = 0;
                    continue;
                }

                boolean forceFinishAllowed =
                        context.getPhase() != AgentPhase.CODING
                                || (codingS1Done && codingS2Done);
                if (forceFinishAllowed && hasSuccessfulCodeChange(context)) {
                    log.warn(
                            "read-only loop ({} rounds) in phase={} but code changes already exist,"
                                    + " go to VERIFY to re-check. planKey={}",
                            readOnlyRounds, context.getPhase(), planKey);
                    // 不再直接 FINISH，而是进入 VERIFY 重新验证。
                    // VERIFY 通过后自然 FINISH；VERIFY 失败则再次进入 REPAIR。
                    context.setPhase(AgentPhase.VERIFY);
                    readOnlyRounds = 0;
                    continue;
                }
                if (context.getPhase() == AgentPhase.CODING) {
                    log.warn(
                            "pure read-only loop detected ({} rounds), force to REPAIR,"
                                    + " planKey={}", readOnlyRounds, planKey);
                    context.getVerifyErrors().clear();
                    context.getVerifyErrors().add(
                            "检测到连续执行纯只读计划（" + readOnlyRounds + " 轮），任务尚未完成。"
                                    + "禁止继续仅读取文件，必须根据已读取内容生成写入动作"
                                    + "（patch_file 或 write_file）。"
                                    + "若读取的文件路径不存在，请先使用 search_code 定位真实路径，"
                                    + "或直接基于已有观察结果生成代码写入动作。"
                    );
                    context.setPhase(AgentPhase.REPAIR);
                    // 重置计数，让 REPAIR 阶段重新计数
                    readOnlyRounds = 0;
                    continue;
                }
                // REPAIR 阶段反复只读且没有任何成功修改：注入更强的引导信息并重置计数，给 LLM 再一次机会。
                log.warn(
                        "REPAIR still falling into read-only loop ({} rounds),"
                                + " inject stronger guidance and retry. planKey={}",
                        readOnlyRounds, planKey);
                context.getVerifyErrors().clear();
                context.getVerifyErrors().add(
                        "检测到 REPAIR 阶段连续执行纯只读计划（" + readOnlyRounds + " 轮），任务尚未完成。"
                                + "你必须立即基于 OBSERVATIONS 中已读取的文件内容生成写入动作"
                                + "（patch_file 或 write_file），禁止再次生成只读 read_file 计划。"
                                + "若之前读取的文件路径不正确，请直接使用 search_code 重新定位目标类，"
                                + "然后对定位到的真实文件执行 patch_file。"
                );
                // 重置计数，给 LLM 再一轮机会
                readOnlyRounds = 0;
                continue;
            }



            if (executedPlans.contains(planKey)) {
                // 检测到重复计划：注入引导信息并强制进入 REPAIR 阶段，
                // 让 LLM 基于已读取内容重新规划并生成写入动作，避免"只读不写"的无效循环。
                log.warn("duplicate plan detected, force to REPAIR to avoid timeout loop {}", planKey);
                context.getVerifyErrors().clear();
                context.getVerifyErrors().add(
                        "检测到重复的计划（" + planKey + "），任务尚未完成。"
                                + "必须生成写入动作（patch_file/write_file），禁止再次生成相同的只读 read_file 计划。"
                                + "请直接基于 OBSERVATIONS 中已读取的文件内容生成 patch_file 或 write_file。"
                );
                context.setPhase(AgentPhase.REPAIR);
                // 清空已执行计划集合，避免 REPAIR 阶段生成的计划被误判为重复
                executedPlans.clear();
                // 重置只读计数，让 REPAIR 阶段重新计数
                readOnlyRounds = 0;
                continue;
            }
            executedPlans.add(planKey);

            List<ToolCallResult> results = toolExecutor.execute(plan, context);
            if (shouldStopRepeatedFailure(results, failureCounts)) {
                context.setPhase(AgentPhase.FINISH);
                log.warn("stop repeated tool failures");
                return;
            }

            ProjectIndex index = projectIndexer.build(context.getObservations());
            context.setProjectIndex(index);

            AgentPhase current = context.getPhase();
            AgentPhase next = phaseManager.next(current, context);
            log.info("phase change {} -> {}", current, next);

            // ===== CODING 双子行程：在进入 VERIFY 前拦截，驱动 S1 -> S2 两次生成 =====
            // 若 CODING 阶段已完成 S1 业务代码落盘且 S2 测试类尚未生成，则先切到 S2_TEST
            // 再生成测试类，而不是直接进入 VERIFY；只有 S1、S2 都完成后才放行进入 VERIFY。
            if (current == AgentPhase.CODING && next == AgentPhase.VERIFY) {
                boolean didWriteMain = codingS1Done
                        || hasWrittenMainCode(context, results);
                if (didWriteMain && !codingS1Done) {
                    codingS1Done = true;
                }
                // S2 测试类是否落盘的判断必须区分当前子行程：
                // - S1_BUSINESS 阶段：即使 LLM 违规写了 src/test 文件，也不应标记 S2 完成，
                //   否则 S2 子行程会被跳过，测试类质量无法保证。
                // - S2_TEST 阶段：只有此时写入 src/test 才算 S2 真正完成。
                if (context.getCodingTrack() == CodingTrack.S2_TEST) {
                    boolean didWriteTest = codingS2Done
                            || hasWrittenTestCode(context, results);
                    if (didWriteTest) {
                        codingS2Done = true;
                    }
                }
                if (codingS1Done && !codingS2Done) {
                    // S1 完成、S2 未完成：不进入 VERIFY，留在 CODING 并切到 S2 生成测试类。
                    log.info("S1 business code done, switch to S2 to generate test class");
                    context.setPhase(AgentPhase.CODING);
                    context.setCodingTrack(CodingTrack.S2_TEST);
                    readOnlyRounds = 0;
                    continue;
                }
            }

            context.setPhase(next);
        }
        log.warn("agent reach max iteration");
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
        VerifyResult verifyResult = verifyEngine.verify(context);
        if (verifyResult.isSuccess()) {
            context.getVerifyErrors().clear();
            context.setPhase(AgentPhase.FINISH);
            return true;
        }
        context.setPhase(AgentPhase.REPAIR);
        context.getVerifyErrors().clear();
        context.getVerifyErrors().addAll(verifyResult.getErrors());

        // 测试执行过且失败：补充 mvn 输出关键摘要（前 5000 字符），
        // 帮助 REPAIR 阶段 LLM 看到具体编译错误 / 断言失败位置。
        if (verifyResult.isTestExecuted() && !verifyResult.isTestPassed()) {
            String output = verifyResult.getTestOutput();
            if (output != null && !output.isEmpty()) {
                String summary = output.length() > 5000
                        ? output.substring(0, 5000) + "\n... (输出过长，已截断)"
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