package com.cmbchina.llm.config;


import com.cmbchina.llm.agent.Agent;
import com.cmbchina.llm.agent.AgentContextFactory;
import com.cmbchina.llm.runtime.AgentRuntime;
import com.cmbchina.llm.trace.TraceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class AgentConfig {


    @Bean
    public Agent codingAgent(
            AgentRuntime runtime,
            TraceService traceService,
            AgentContextFactory agentContextFactory
    ){


        return Agent.builder()
                .id("default-agent")
                .name("Mini-Codex-Agent")
                .runtime(runtime)
                .traceService(traceService)
                .agentContextFactory(agentContextFactory)
                .build();


    }


}
