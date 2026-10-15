package com.cmbchina.llm.service;

import com.cmbchina.llm.agent.AgentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent异步任务状态存储。
 *
 * <p>使用 {@link ConcurrentHashMap} 在内存中维护异步任务的执行状态与结果，
 * 供 Controller 提交任务后通过任务ID轮询查询。任务状态包括：</p >
 * <ul>
 *     <li>PENDING — 任务已提交，正在执行中</li>
 *     <li>SUCCESS — 任务执行成功，结果已就绪</li>
 *     <li>FAILED  — 任务执行失败，错误信息已就绪</li>
 * </ul>
 *
 * @author IT006901
 * @date 2026-09-15 15:30:00
 */
@Slf4j
@Component
public class AgentTaskStore {

    /**
     * 任务状态枚举
     */
    public enum TaskStatus {
        /**
         * 任务已提交，正在执行中
         */
        PENDING,
        /**
         * 任务执行成功
         */
        SUCCESS,
        /**
         * 任务执行失败
         */
        FAILED
    }

    /**
     * 任务状态记录
     */
    public static class TaskRecord {

        /**
         * 任务状态
         */
        private final TaskStatus status;

        /**
         * 任务执行结果（成功时非空）
         */
        private final AgentResult result;

        /**
         * 错误信息（失败时非空）
         */
        private final String error;

        /**
         * 任务创建时间戳（毫秒）
         */
        private final long createdAt;

        public TaskRecord(TaskStatus status, AgentResult result, String error) {
            this.status = status;
            this.result = result;
            this.error = error;
            this.createdAt = System.currentTimeMillis();
        }

        public TaskStatus getStatus() {
            return status;
        }

        public AgentResult getResult() {
            return result;
        }

        public String getError() {
            return error;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }

    /**
     * 任务存储映射：taskId -> TaskRecord
     */
    private final Map<String, TaskRecord> store = new ConcurrentHashMap<>();

    /**
     * 记录任务为"执行中"状态。
     *
     * @param taskId 任务ID
     */
    public void markPending(String taskId) {
        store.put(taskId, new TaskRecord(TaskStatus.PENDING, null, null));
    }

    /**
     * 记录任务为"执行成功"状态。
     *
     * @param taskId 任务ID
     * @param result Agent执行结果
     */
    public void markSuccess(String taskId, AgentResult result) {
        store.put(taskId, new TaskRecord(TaskStatus.SUCCESS, result, null));
        log.info("agent async task success taskId={}", taskId);
    }

    /**
     * 记录任务为"执行失败"状态。
     *
     * @param taskId 任务ID
     * @param error  错误信息
     */
    public void markFailed(String taskId, String error) {
        store.put(taskId, new TaskRecord(TaskStatus.FAILED, null, error));
        log.warn("agent async task failed taskId={} error={}", taskId, error);
    }

/**
 * 查询任务状态记录。
 *
 * @param taskId 任务ID
 * @return 任务状态记录，不存在时返回 null
 */
public TaskRecord get(String taskId) {
    return store.get(taskId);
}

    /**
     * 移除任务记录（用于清理已查询完毕的任务）。
     *
     * @param taskId 任务ID
     */
    public void remove(String taskId) {
        store.remove(taskId);
    }

}
