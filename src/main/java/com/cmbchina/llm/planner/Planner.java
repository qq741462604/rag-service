package com.cmbchina.llm.planner;


import com.cmbchina.llm.agent.AgentContext;

public interface Planner {



    CodePlan createPlan(
            AgentContext context
    );


}