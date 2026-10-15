package com.cmbchina.llm.agent;


import com.cmbchina.llm.memory.AgentMemoryService;
import com.cmbchina.llm.runtime.AgentRuntime;
import com.cmbchina.llm.trace.AgentTrace;
import com.cmbchina.llm.trace.TraceService;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Agent {

    private String id;
    private String name;
    private AgentRuntime runtime;
    private AgentMemoryService memoryService;
    private TraceService traceService;
    private AgentContextFactory agentContextFactory;

    public AgentResult run(String task) {
        AgentTrace trace = null;
        if (traceService != null) {
            trace = traceService.start(task);
        }
        AgentResult result;
        try {
            if (agentContextFactory == null) {
                return AgentResult.failed("AgentContextFactory is null");
            }
            AgentContext context = agentContextFactory.create(id, task, trace);
            if (runtime == null) {
                return AgentResult.failed("AgentRuntime is null");
            }
            result = runtime.execute(context);
            if (memoryService != null && result != null) {
                memoryService.saveTask(task, result.getMessage());
            }
        } catch (Exception e) {
            result = AgentResult.failed(e.getMessage());
        } finally {
            if (traceService != null && trace != null) {
                traceService.finish(trace);
            }
        }
        return result;
    }
}
