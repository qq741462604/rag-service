package com.cmbchina.llm.agent;


import com.cmbchina.llm.memory.Memory;
import com.cmbchina.llm.memory.MemoryStore;
import com.cmbchina.llm.skill.Skill;
import com.cmbchina.llm.skill.SkillMatcher;
import com.cmbchina.llm.trace.AgentTrace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent上下文工厂。
 *
 * @author yy
 * @date 2026-08-10 00:00:00
 */
@Component
@RequiredArgsConstructor
public class AgentContextFactory {

    private final MemoryStore memoryStore;
    private final SkillMatcher skillMatcher;

    public AgentContext create(String agentId, String task, AgentTrace trace) {
        return AgentContext.builder()
                .agentId(agentId)
                .task(task)
                .trace(trace)
                .phase(AgentPhase.ANALYSIS)
                .memories(safeMemoryQuery(task))
                .observations(new ArrayList<>())
                .skills(loadSkills(task))
                .build();
    }

    private List<Memory> safeMemoryQuery(String task) {
        List<Memory> memories = memoryStore.query(task);
        return memories == null ? new ArrayList<>() : memories;
    }

    private List<Skill> loadSkills(String task) {
        List<Skill> skills = skillMatcher.match(task);
        return skills == null ? new ArrayList<>() : skills;
    }
}
