package com.cmbchina.llm.agent.result;

import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.tool.FileOperationResult;
import com.cmbchina.llm.tool.ToolInput;
import org.springframework.stereotype.Component;


import java.util.ArrayList;
import java.util.List;



@Component
public class CodeChangeExtractor {



    public List<CodeChange> extract(
            List<Observation> observations
    ){


        List<CodeChange> result =
                new ArrayList<>();


        if(observations==null){

            return result;

        }



        for(Observation o:observations){


            if(!o.isSuccess()){

                continue;

            }



            if(!isCodeChangeTool(o.getTool())){

                continue;

            }



            String path =
                    extractPath(
                            o
                    );



            if(path==null){

                continue;

            }



            result.add(
                    CodeChange.builder()
                            .action(
                                    convertAction(
                                            o.getTool()
                                    )
                            )
                            .path(
                                    normalize(path)
                            )
                            .success(true)
                            .build()
            );


        }


        return distinct(result);

    }





    private boolean isCodeChangeTool(
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





    private String extractPath(
            Observation o
    ){

        if(o.getResult()
                instanceof FileOperationResult){

            FileOperationResult result =
                    (FileOperationResult)o.getResult();

            return result.getPath();

        }

        if(o.getInput()
                instanceof ToolInput){


            ToolInput input =
                    (ToolInput)o.getInput();


            return input.getPath();

        }



        return null;

    }





    private String convertAction(
            String tool
    ){

        if("create_file".equals(tool)){

            return "create";

        }


        if("edit_file".equals(tool)){

            return "edit";

        }


        if("patch_file".equals(tool)){

            return "edit";

        }


        return "write";

    }





    private String normalize(
            String path
    ){

        return path.replace("\\","/");

    }





    private List<CodeChange> distinct(
            List<CodeChange> list
    ){

        List<CodeChange> result =
                new ArrayList<>();


        for(CodeChange c:list){


            boolean exists =
                    result.stream()
                            .anyMatch(
                                    x ->
                                            x.getPath()
                                                    .equals(c.getPath())
                            );


            if(!exists){

                result.add(c);

            }

        }


        return result;

    }


}