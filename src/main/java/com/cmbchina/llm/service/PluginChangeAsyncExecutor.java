package com.cmbchina.llm.service;

import com.cmbchina.llm.agent.AgentResult;
import com.cmbchina.llm.agent.result.AgentBatchResult;
import com.cmbchina.llm.agent.result.AgentTaskResult;
import com.cmbchina.llm.controller.request.AgentRequest;
import com.cmbchina.llm.controller.request.AgentTaskRequest;
import com.cmbchina.llm.entity.model.RequirementDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件改造异步执行器
 * 异步执行需求文档中的插件改造点
 *
 * @author IT006901
 * @date 2026-08-26 09:00:00
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PluginChangeAsyncExecutor {

    private final AgentBatchService batchService;

    /**
     * 异步执行插件改造任务
     * 读取需求文档中的插件改造点，组装批量请求后交给AgentBatchService串行执行
     *
     * @param doc 需求文档
     */
    @Async("agentTaskExecutor")
    public void executeAsync(RequirementDoc doc) {
        String eventSourceId = doc != null && doc.getBasicInfo() != null ? doc.getBasicInfo().getEventSourceId() : "unknown";
        try {
            log.info("开始异步执行插件改造任务，事件源ID={}", eventSourceId);

            if (doc.getPluginRequirement() == null
                    || doc.getPluginRequirement().getChangePoints() == null
                    || doc.getPluginRequirement().getChangePoints().isEmpty()) {
                log.info("插件改造异步任务完成：需求文档中没有插件改造点，事件源ID={}", eventSourceId);
                return;
            }

            // 组装批量任务请求
            List<AgentTaskRequest> tasks = new ArrayList<>();
            List<RequirementDoc.PluginChangePoint> changePoints = doc.getPluginRequirement().getChangePoints();
            for (RequirementDoc.PluginChangePoint changePoint : changePoints) {
                AgentTaskRequest taskRequest = new AgentTaskRequest();
                taskRequest.setId(changePoint.getName());
                taskRequest.setTask(buildChangePointTask(changePoint));
                tasks.add(taskRequest);
            }

            log.info("组装批量改造任务完成，事件源ID={}，改造点数量={}", eventSourceId, tasks.size());

            // 构造批量请求并执行
            AgentRequest request = new AgentRequest();
            request.setTasks(tasks);
            AgentResult result = batchService.run(request);

            // 打印执行结果日志
            logBatchResult(eventSourceId, result);

        } catch (Exception e) {
            log.error("插件改造异步任务异常，事件源ID={}，异常信息：{}", eventSourceId, e.getMessage(), e);
        }
    }

    /**
     * 打印批量执行结果日志
     *
     * @param eventSourceId 事件源ID
     * @param result Agent执行结果
     */
    private void logBatchResult(String eventSourceId, AgentResult result) {
        if (result == null) {
            log.warn("插件改造异步任务完成：执行结果为空，事件源ID={}", eventSourceId);
            return;
        }

        if (result.isSuccess()) {
            log.info("=== 插件改造异步任务完成 === 事件源ID={}，批量执行成功，结果：{}", eventSourceId, result.getMessage());
        } else {
            log.warn("=== 插件改造异步任务完成 === 事件源ID={}，批量执行失败，结果：{}", eventSourceId, result.getMessage());
        }

        // 打印每个改造点的执行结果
        if (result.getData() instanceof AgentBatchResult) {
            AgentBatchResult batchResult = (AgentBatchResult) result.getData();
            if (batchResult.getResults() != null) {
                for (AgentTaskResult taskResult : batchResult.getResults()) {
                    if (taskResult.isSuccess()) {
                        log.info("改造点执行成功：{}，结果：{}", taskResult.getId(), taskResult.getMessage());
                    } else {
                        log.warn("改造点执行失败：{}，结果：{}", taskResult.getId(), taskResult.getMessage());
                    }
                }
            }
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
    }
}