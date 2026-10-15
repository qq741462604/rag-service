package com.cmbchina.llm.prompt;


import com.cmbchina.llm.config.AgentHomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;



@Service
@RequiredArgsConstructor
public class PromptLoader {


    private final AgentHomeService agentHomeService;



    public String load(
            String name
    ){


        File file =
                agentHomeService.resolve(
                        ".ai/prompts/"
                                + name
                                + ".md"
                );


        if(!file.exists()){


            throw new RuntimeException(
                    "prompt not found:"
                            +
                            file.getAbsolutePath()
            );

        }



        try{


            return new String(
                    Files.readAllBytes(
                            file.toPath()
                    ),
                    StandardCharsets.UTF_8
            );


        }catch(Exception e){


            throw new RuntimeException(
                    "load prompt failed",
                    e
            );

        }


    }

}