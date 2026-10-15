package com.cmbchina.llm.runtime;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRuntime {


    private final AgentExecutor executor;



    public AgentResult execute(AgentContext context){


        try {


            return executor.execute(context);


        }catch(Exception e){


            log.error(
                    "runtime execute error",
                    e
            );

            return AgentResult.failed(
                    e.getClass().getName()
                            +
                            ":"
                            +
                            e.getMessage()
            );
        }

    }



}