package com.cmbchina.llm.agent.phase;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import org.springframework.stereotype.Component;

import java.util.List;



@Component
public class PhaseManager {


    private final List<AgentPhaseHandler> handlers;



    public PhaseManager(
            List<AgentPhaseHandler> handlers
    ){

        this.handlers=handlers;

    }



    public AgentPhase next(
            AgentPhase current,
            AgentContext context
    ){


        return handlers.stream()
                .filter(
                        h ->
                                h.phase()==current
                )
                .findFirst()
                .map(
                        h ->
                                h.next(context)
                )
                .orElse(
                        AgentPhase.FINISH
                );


    }


}