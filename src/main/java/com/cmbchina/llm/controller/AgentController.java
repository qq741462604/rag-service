package com.cmbchina.llm.controller;


import com.cmbchina.llm.agent.Agent;
import com.cmbchina.llm.agent.AgentResult;
import com.cmbchina.llm.controller.request.AgentRequest;
import com.cmbchina.llm.entity.model.RequirementDoc;
import com.cmbchina.llm.service.AgentAsyncExecutor;
import com.cmbchina.llm.service.AgentBatchService;
import com.cmbchina.llm.service.AgentTaskStore;
import com.cmbchina.llm.skill.SkillMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


/**
 * Agent插件改造入口
 * 接收插件改造点任务，通过技能匹配后执行自动开发
 *
 * @author IT006901
 * @date 2026-08-14
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final Agent agent;

    private final SkillMatcher skillMatcher;

    private final AgentBatchService batchService;

    private final AgentAsyncExecutor asyncExecutor;

    private final AgentTaskStore taskStore;

    /**
     * 单任务异步提交后立即返回任务ID，通过 /agent/task/{taskId} 轮询查询结果，
     * 避免 Agent 多轮迭代调用大模型导致网关 504 超时。
     *
     * @param request Agent请求，task为改造点描述
     * @return 单任务返回包含 taskId 的异步提交结果；批量任务仍同步执行
     */
    @PostMapping("/run")
    public AgentResult run(
            @RequestBody AgentRequest request
    ) {
        if (batchService.isBatchRequest(request)) {
            return batchService.run(request);
        }

        String task = request == null ? null : request.getTask();
        if (!skillMatcher.hasBusinessMatch(task)) {
            return AgentResult.success("未匹配到可执行的技能，当前需求暂不支持自动开发。请先将该需求转换为 skill 后再提交。");
        }

        // 单任务异步提交，立即返回任务ID
        String taskId = UUID.randomUUID().toString().replace("-", "");
        taskStore.markPending(taskId);
        asyncExecutor.executeAsync(taskId, task);
        log.info("agent async task submitted taskId={} task={}", taskId, task);

        Map<String, Object> taskInfo = new HashMap<>();
        taskInfo.put("taskId", taskId);
        taskInfo.put("queryUrl", "/agent/task/" + taskId);

        return AgentResult.builder()
                .success(true)
                .data(taskInfo)
                .build();
    }

    /**
     * 查询异步任务执行状态与结果
     *
     * @param taskId 任务ID（由 /agent/run 异步提交时返回）
     * @return 任务状态与执行结果
     */
    @GetMapping("/task/{taskId}")
    public AgentResult queryTask(
            @PathVariable String taskId
    ) {
        AgentTaskStore.TaskRecord record = taskStore.get(taskId);
        if (record == null) {
            return AgentResult.failed("任务不存在或已过期: " + taskId);
        }

        AgentTaskStore.TaskStatus status = record.getStatus();
        if (status == AgentTaskStore.TaskStatus.PENDING) {
            Map<String, Object> taskInfo = new HashMap<>();
            taskInfo.put("taskId", taskId);
            taskInfo.put("status", status.name());
            return AgentResult.builder()
                    .success(true)
                    .message("任务执行中，请稍后重试")
                    .data(taskInfo)
                    .build();
        }

        if (status == AgentTaskStore.TaskStatus.SUCCESS) {
            return record.getResult();
        }

        // FAILED
        return AgentResult.failed(record.getError());
    }

    /**
     * 根据需求文档批量执行插件改造
     * 遍历需求文档中的所有插件改造点，逐个提交给Agent执行
     *
     * @param doc 需求文档
     * @return 每个改造点的执行结果汇总
     */
    @PostMapping("/run-requirement")
    public String runRequirement(@RequestBody RequirementDoc doc) {
        try {
            if (doc == null || doc.getPluginRequirement() == null
                    || doc.getPluginRequirement().getChangePoints() == null
                    || doc.getPluginRequirement().getChangePoints().isEmpty()) {
                return "错误：需求文档中没有插件改造点";
            }

            StringBuilder resultBuilder = new StringBuilder();
            int total = doc.getPluginRequirement().getChangePoints().size();
            int success = 0;
            int fail = 0;

            for (RequirementDoc.PluginChangePoint changePoint : doc.getPluginRequirement().getChangePoints()) {
                // 构造改造点任务描述
                String task = buildChangePointTask(changePoint);
                log.info("开始执行插件改造点：{}", changePoint.getName());

                // 同步执行改造（批量场景逐个执行，由调用方控制整体耗时）
                AgentResult result = agent.run(task);

                resultBuilder.append("【").append(changePoint.getName()).append("】");
                if (result != null && result.isSuccess()) {
                    success++;
                    resultBuilder.append("成功：").append(result.getMessage());
                } else {
                    fail++;
                    resultBuilder.append("失败：").append(result != null ? result.getMessage() : "未知错误");
                }
                resultBuilder.append("\n");
            }

            // 如果有决策码值关系变更，生成drools.properties
            if (doc.getPluginRequirement().getDecisionCodeRelation() != null) {
                String droolsContent = generateDroolsProperties(doc);
                resultBuilder.append("\n【决策码值配置】\n").append(droolsContent);
            }

            resultBuilder.insert(0, String.format("插件改造完成，共%d个改造点，成功%d个，失败%d个\n",
                    total, success, fail));

            log.info("插件改造批量执行完成：总计{}，成功{}，失败{}", total, success, fail);
            return resultBuilder.toString();

        } catch (Exception e) {
            log.error("批量执行插件改造异常：{}", e);
            return "错误：批量执行插件改造异常 - " + e.getMessage();
        }
    }

    /**
     * 构造改造点任务描述
     *
     * @param changePoint 改造点信息
     * @return 任务描述字符串
     */
    private String buildChangePointTask(RequirementDoc.PluginChangePoint changePoint) {
        StringBuilder sb = new StringBuilder();
        sb.append(changePoint.getDescription());

        if (changePoint.getInputFields() != null && !changePoint.getInputFields().isEmpty()) {
            sb.append("，输入字段：").append(String.join(", ", changePoint.getInputFields()));
        }
        if (changePoint.getOutputFields() != null && !changePoint.getOutputFields().isEmpty()) {
            sb.append("，输出字段：").append(String.join(", ", changePoint.getOutputFields()));
        }
        if (changePoint.getLogic() != null && !changePoint.getLogic().isEmpty()) {
            sb.append("，加工逻辑：").append(changePoint.getLogic());
        }

        // 外调接口信息
        if ("外调接口".equals(changePoint.getType()) && changePoint.getExternalApi() != null) {
            RequirementDoc.ExternalApi api = changePoint.getExternalApi();
            sb.append("，接口地址：").append(api.getUrl());
            sb.append("，请求方式：").append(api.getMethod());
            if (api.getRequestParams() != null) {
                sb.append("，请求参数：").append(api.getRequestParams());
            }
            if (api.getResponseParams() != null) {
                sb.append("，响应参数：").append(api.getResponseParams());
            }
        }

        return sb.toString();
    }/**
     * 根据需求文档生成drools.properties配置文件内容
     *
     * @param doc 需求文档
     * @return drools.properties配置内容
     */
    private String generateDroolsProperties(RequirementDoc doc) {
        if (doc == null || doc.getPluginRequirement() == null
                || doc.getPluginRequirement().getDecisionCodeRelation() == null) {
            return "";
        }

        RequirementDoc.DecisionCodeRelation relation = doc.getPluginRequirement().getDecisionCodeRelation();
        StringBuilder sb = new StringBuilder();

        // 默认策略
        sb.append("# 默认策略，什么策略都不中的时候，返回该策略\n");
        sb.append("drools.defaultValue=").append(relation.getDefaultValue()).append("\n\n");

        // 决策码值规则
        if (relation.getRules() != null) {
            for (RequirementDoc.DecisionRule rule : relation.getRules()) {
                sb.append("# ").append(rule.getCode()).append(". ").append(rule.getDescription()).append("\n");
                sb.append("drools.rules.").append(rule.getCode()).append("=")
                        .append(rule.getMinScore()).append(",").append(rule.getMaxScore()).append("\n\n");
            }
        }

        return sb.toString();
    }
}
