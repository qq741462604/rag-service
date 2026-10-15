package com.cmbchina.llm.agent.phase;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import org.springframework.stereotype.Component;



@Component
public class CodingPhaseHandler
        implements AgentPhaseHandler {



    @Override
    public AgentPhase phase(){

        return AgentPhase.CODING;

    }



    @Override
    public AgentPhase next(
            AgentContext context
    ){

        boolean failedChange =
                context.getLastObservations()
                        .stream()
                        .anyMatch(
                                o ->
                                        isFileChangeTool(o.getTool())
                                                &&
                                                !o.isSuccess()
                        );


        if(failedChange){

            return AgentPhase.CODING;

        }


        boolean changed =
                context.getLastObservations()
                        .stream()
                        .anyMatch(
                                o ->
                                        isFileChangeTool(o.getTool())
                                                &&
                                                o.isSuccess()
                        );





        if(changed){

            return AgentPhase.VERIFY;

        }


        return AgentPhase.CODING;

    }


    private boolean isFileChangeTool(
            String tool
    ){


        return "create_file".equals(tool)
                ||
                "write_file".equals(tool)
                ||
                "edit_file".equals(tool)
                ||
                "patch_file".equals(tool);

    }

}
