package com.cmbchina.llm.service;

import com.cmbchina.llm.agent.Agent;
import com.cmbchina.llm.agent.AgentResult;
import com.cmbchina.llm.agent.result.AgentBatchResult;
import com.cmbchina.llm.agent.result.AgentTaskResult;
import com.cmbchina.llm.agent.result.CodeChange;
import com.cmbchina.llm.controller.request.AgentRequest;
import com.cmbchina.llm.controller.request.AgentTaskRequest;
import com.cmbchina.llm.skill.SkillMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量需求串行执行服务。
 *
 * @author yy
 * @date 2026-08-10 11:00:00
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBatchService {

    private static final String UNKNOWN_TASK_MESSAGE =
            "未匹配到可执行的技能，当前需求暂不支持自动开发。请先将该需求转换为 skill 后再提交。";

    private final Agent agent;

    private final SkillMatcher skillMatcher;

    public boolean isBatchRequest(AgentRequest request) {
        return request != null
                && request.getTasks() != null;
    }

    public AgentResult run(AgentRequest request) {
        List<AgentTaskRequest> tasks = request.getTasks();
        if (tasks.isEmpty()) {
            return buildResult(false, true, null, null, "batch tasks is empty", new ArrayList<>(), new ArrayList<>());
        }

        List<AgentTaskResult> taskResults = new ArrayList<>();
        List<CodeChange> changes = new ArrayList<>();

        for (int i = 0; i < tasks.size(); i++) {
            AgentTaskRequest taskRequest = tasks.get(i);
            int index = i + 1;
            String task = taskRequest == null ? null : taskRequest.getTask();
            String taskId = taskRequest == null ? null : taskRequest.getId();

            log.info("batch task start index={} id={} task={}", index, taskId, task);

            AgentResult result = executeSingle(index, taskId, task);
            taskResults.add(toTaskResult(index, taskId, task, result));
            if (result.getChanges() != null) {
                addDistinctChanges(changes, result.getChanges());
            }

            if (!result.isSuccess()) {
                String failureReason = result.getMessage();
                log.warn(
                        "batch task failed, stop remaining tasks index={} id={} reason={}",
                        index,
                        taskId,
                        failureReason
                );
                return buildResult(false, true, index, taskId, failureReason, taskResults, changes);
            }
        }

        log.info("batch task finish count={}", taskResults.size());
        return buildResult(true, false, null, null, null, taskResults, changes);
    }

    private AgentResult executeSingle(int index, String taskId, String task) {
        if (task == null || task.trim().isEmpty()) {
            return AgentResult.failed("task is empty");
        }

        if (!skillMatcher.hasBusinessMatch(task)) {
            return AgentResult.failed(UNKNOWN_TASK_MESSAGE);
        }

        try {
            AgentResult result = agent.run(task);
            if (result == null) {
                return AgentResult.failed("agent result is null");
            }
            return result;
        } catch (Exception e) {
            log.error("batch task execute error index={} id={}", index, taskId, e);
            return AgentResult.failed(e.getClass().getName() + ":" + e.getMessage());
        }
    }

    private AgentTaskResult toTaskResult(
            int index,
            String taskId,
            String task,
            AgentResult result
    ) {
        return AgentTaskResult.builder()
                .index(index)
                .id(taskId)
                .task(task)
                .success(result.isSuccess())
                .message(result.getMessage())
                .changes(result.getChanges())
                .build();
    }

    private AgentResult buildResult(
            boolean success,
            boolean stopped,
            Integer failedIndex,
            String failedTaskId,
            String failureReason,
            List<AgentTaskResult> results,
            List<CodeChange> changes
    ) {
        String message = success
                ? "batch execute success"
                : "batch execute failed, stopped at task " + failedIndex + ": " + failureReason;

        return AgentResult.builder()
                .success(success)
                .message(message)
                .data(
                        AgentBatchResult.builder()
                                .stopped(stopped)
                                .failedIndex(failedIndex)
                                .failedTaskId(failedTaskId)
                                .failureReason(failureReason)
                                .results(results)
                                .build()
                )
                .changes(changes)
                .build();
    }

    private void addDistinctChanges(List<CodeChange> target, List<CodeChange> source) {
        for (CodeChange change : source) {
            if (change == null || change.getPath() == null) {
                continue;
            }
            if (!containsPath(target, change.getPath())) {
                target.add(change);
            }
        }
    }

    private boolean containsPath(List<CodeChange> changes, String path) {
        for (CodeChange change : changes) {
            if (path.equals(change.getPath())) {
                return true;
            }
        }
        return false;
    }

}