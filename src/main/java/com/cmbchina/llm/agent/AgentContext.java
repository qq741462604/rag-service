package com.cmbchina.llm.agent;


import com.cmbchina.llm.agent.knowledge.KnowledgeContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.memory.Memory;
import com.cmbchina.llm.project.ProjectIndex;
import com.cmbchina.llm.trace.AgentTrace;
import lombok.Builder;
import lombok.Data;
import com.cmbchina.llm.skill.Skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;


@Data
@Builder
public class AgentContext {


    private String agentId;


    private String task;

    private ProjectIndex projectIndex;

    //    private String workspace;

    private AgentTrace trace;

    private String lastPlanHash;

    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();


    @Builder.Default
    private List<Memory> memories = new ArrayList<>();

    private KnowledgeContext knowledge;
    /**
     * 工具观察结果
     */
    @Builder.Default
    private List<Observation> observations = new ArrayList<>();
    @Builder.Default
    private List<Observation> lastObservations = new ArrayList<>();

    @Builder.Default
    private List<Skill> skills = new ArrayList<>();

    @Builder.Default
    private AgentStatus status = AgentStatus.IDLE;

    /**
     * Agent当前执行阶段
     */
    @Builder.Default
    private AgentPhase phase = AgentPhase.ANALYSIS;

    /**
     * 验证失败原因
     */
    @Builder.Default
    private List<String> verifyErrors = new ArrayList<>();

    /**
     * CODING 阶段内的子行程标记（S1 业务代码 / S2 测试类）。
     * 业务代码生成与测试类生成分离（两次 QwenPlanner 调用），同一 AgentLoop 业务链内驱动。
     */
    @Builder.Default
    private CodingTrack codingTrack = CodingTrack.S1_BUSINESS;

    /**
     * 当前任务已通过产物检查的模板，读取成功本身不代表模板实现完成。
     */
    @Builder.Default
    private Set<String> completedTemplates = new LinkedHashSet<>();

    /**
     * 当前模板尚未满足的结构约束，单独保存以免覆盖工具或模型错误。
     */
    @Builder.Default
    private List<String> templateErrors = new ArrayList<>();

    /**
     * 当前任务实际发起的规划模型调用数，不含系统直接构造的模板读取计划。
     */
    @Builder.Default
    private int modelCalls = 0;

}

