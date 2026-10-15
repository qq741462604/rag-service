package com.cmbchina.llm.project;


import com.cmbchina.llm.workspace.WorkspaceIgnoreMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



import java.io.File;



@Component
@RequiredArgsConstructor
public class ProjectAnalyzer {


    private final WorkspaceIgnoreMatcher matcher;

    public ProjectIndex analyze(
            String path
    ){


        ProjectIndex index =
                new ProjectIndex();


        index.setRoot(path);



        scan(
                new File(path),
                index
        );


        return index;


    }





    private void scan(
            File file,
            ProjectIndex index
    ){

        if(matcher.match(file)){
            return;
        }

        if(file.isFile()){


            index.add(
                    ProjectFile.builder()
                            .path(
                                    file.getPath()
                            )
                            .type(
                                    getType(file)
                            )
                            .size(
                                    file.length()
                            )
                            .source(
                                    isSource(file)
                            )
                            .build()
            );


            return;

        }




        File[] files =
                file.listFiles();



        if(files==null){

            return;

        }



        for(File child:files){

            scan(
                    child,
                    index
            );

        }


    }





    private String getType(
            File file
    ){

        String name =
                file.getName();


        int index =
                name.lastIndexOf(".");


        if(index==-1){

            return "";

        }


        return name.substring(
                index+1
        );


    }




    private boolean isSource(
            File file
    ){

        String name =
                file.getName();


        return name.endsWith(".java")
                ||
                name.endsWith(".xml")
                ||
                name.endsWith(".yml");


    }


}