package com.cmbchina.llm.agent.phase;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;

public interface AgentPhaseHandler {


    AgentPhase phase();


    AgentPhase next(AgentContext context);


}