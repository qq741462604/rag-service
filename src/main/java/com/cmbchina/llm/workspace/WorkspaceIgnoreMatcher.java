package com.cmbchina.llm.workspace;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;



@Component
@RequiredArgsConstructor
public class WorkspaceIgnoreMatcher {


    private final WorkspaceService workspaceService;



    private volatile List<String> ignores;



    public boolean match(File file){


        init();



        String path =
                normalize(
                        workspaceService.relativePath(file)
                );



        if(path.isEmpty()){
            return false;
        }

        String name =
                file.getName();



        for(String rule:ignores){


            rule =
                    normalize(rule);



            /*
             * 目录名匹配
             */
            if(name.equals(rule)){

                return true;

            }



            /*
             * 路径匹配
             */
            if(("/"+path+"/").contains("/"+rule+"/")){

                return true;

            }



            /*
             * 文件后缀匹配
             */
            if(rule.startsWith("*.")){

                if(name.endsWith(
                        rule.substring(1)
                )){

                    return true;

                }

            }


        }


        return false;

    }






    private void init(){


        if(ignores!=null){

            return;

        }



        synchronized(this){


            if(ignores!=null){

                return;

            }


            ignores =
                    new ArrayList<>();



            File file =
                    workspaceService.resolve(
                            ".ai/ignore/workspace.ignore"
                    );



            if(file.exists()){


                load(file);


            }



            addDefault();



        }


    }






    private void load(
            File file
    ){


        try(
                BufferedReader reader =
                        Files.newBufferedReader(
                                file.toPath(),
                                StandardCharsets.UTF_8
                        )
        ){


            String line;



            while(
                    (line=reader.readLine())
                            !=null
            ){


                line=line.trim();


                if(line.isEmpty()
                        ||
                        line.startsWith("#")){


                    continue;

                }



                ignores.add(line);


            }


        }catch(Exception e){


            throw new RuntimeException(
                    e
            );


        }


    }







    private void addDefault(){


        /*
         * 系统级永远忽略
         */
        ignores.add(".git");

        ignores.add(".idea");

        ignores.add("target");

        ignores.add("node_modules");


    }





    private String normalize(
            String value
    ){

        return value
                .replace("\\","/")
                .trim();

    }


}
