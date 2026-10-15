package com.cmbchina.llm.agent.phase;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import org.springframework.stereotype.Component;


@Component
public class FinishPhaseHandler
        implements AgentPhaseHandler {



    @Override
    public AgentPhase phase(){

        return AgentPhase.FINISH;

    }



    @Override
    public AgentPhase next(
            AgentContext context
    ){

        return AgentPhase.FINISH;

    }


}