package com.cmbchina.llm.project;


import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.tool.SearchMatch;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class ProjectIndexer {


    public ProjectIndex build(
            List<Observation> observations
    ){


        ProjectIndex index =
                new ProjectIndex();



        if(observations==null){

            return index;

        }



        for(Observation observation:observations){


            if(!observation.isSuccess()){

                continue;

            }


            Object result =
                    observation.getResult();



            if(!(result instanceof List)){

                continue;

            }



            List<?> list =
                    (List<?>) result;



            for(Object item:list){


                String path =
                        extractPath(item);



                if(path==null){

                    continue;

                }



                ProjectFile file =
                        buildProjectFile(path);



                classify(
                        index,
                        file
                );


            }


        }


        return index;

    }






    private String extractPath(
            Object item
    ){


        if(item instanceof String){

            return (String)item;

        }



        if(item instanceof SearchMatch){

            return ((SearchMatch)item)
                    .getPath();

        }


        return null;

    }






    private ProjectFile buildProjectFile(
            String path
    ){


        ProjectFile file =
                new ProjectFile();



        file.setPath(
                path
        );


        file.setType(
                getType(path)
        );


        return file;

    }






    private void classify(
            ProjectIndex index,
            ProjectFile file
    ){



        String path =
                file.getPath();



        if(path.contains("Controller")){


            index.getControllers()
                    .add(file);

        }



        if(path.contains("Service")){


            index.getServices()
                    .add(file);

        }



        if(path.contains("Entity")){


            index.getEntities()
                    .add(file);

        }



        if(path.contains("Mapper")){


            index.getMappers()
                    .add(file);

        }


    }






    private String getType(
            String path
    ){


        int index =
                path.lastIndexOf(".");


        if(index<0){

            return "";

        }


        return path.substring(
                index+1
        );


    }


}