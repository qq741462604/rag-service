package com.cmbchina.llm.agent.phase;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import com.cmbchina.llm.verify.VerifyResult;
import org.springframework.stereotype.Component;


@Component
public class VerifyPhaseHandler
        implements AgentPhaseHandler {


    @Override
    public AgentPhase phase(){

        return AgentPhase.VERIFY;

    }



    @Override
    public AgentPhase next(
            AgentContext context
    ){
        Object result =
                context.getVariables()
                        .get("verify_result");


        if(result==null){

            return AgentPhase.VERIFY;

        }

        VerifyResult verifyResult =
                (VerifyResult)result;


        if(verifyResult.isSuccess()){

            return AgentPhase.FINISH;

        }


        return AgentPhase.REPAIR;




    }

}