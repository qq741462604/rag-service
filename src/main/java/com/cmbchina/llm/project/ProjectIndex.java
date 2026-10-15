package com.cmbchina.llm.project;


import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


@Data
public class ProjectIndex {



    /**
     * 项目根目录
     */
    private String root;



    /**
     * 所有文件
     */
    private List<ProjectFile> files =
            new ArrayList<>();



    /**
     * Controller
     */
//    private List<ProjectFile> controllers =
//            new ArrayList<>();
    private Set<ProjectFile> controllers =
            new HashSet<>();


    private Set<ProjectFile> services =
            new HashSet<>();


    private Set<ProjectFile> entities =
            new HashSet<>();


    private Set<ProjectFile> mappers =
            new HashSet<>();
//    /**
//     * Service
//     */
//    private List<ProjectFile> services =
//            new ArrayList<>();
//
//
//
//    /**
//     * Entity
//     */
//    private List<ProjectFile> entities =
//            new ArrayList<>();
//
//
//
//    /**
//     * Mapper
//     */
//    private List<ProjectFile> mappers =
//            new ArrayList<>();





    public void add(
            ProjectFile file
    ){

        files.add(file);



        String path =
                file.getPath();



        if(path.contains("controller")
                ||
                path.contains("Controller")){


            controllers.add(file);

        }



        if(path.contains("service")
                ||
                path.contains("Service")){


            services.add(file);

        }



        if(path.contains("entity")
                ||
                path.contains("Entity")
                ||
                path.contains("domain")){


            entities.add(file);

        }



        if(path.contains("mapper")
                ||
                path.contains("Mapper")){


            mappers.add(file);

        }

    }


}