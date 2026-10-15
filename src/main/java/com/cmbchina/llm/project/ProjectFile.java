package com.cmbchina.llm.project;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectFile {


    /**
     * 文件路径
     */
    private String path;



    /**
     * 文件类型
     */
    private String type;



    /**
     * 文件大小
     */
    private long size;



    /**
     * 是否代码文件
     */
    private boolean source;


    @Override
    public boolean equals(Object o){

        if(this==o){
            return true;
        }


        if(!(o instanceof ProjectFile)){
            return false;
        }


        ProjectFile other =
                (ProjectFile)o;


        return path!=null
                &&
                path.equals(other.path);

    }



    @Override
    public int hashCode(){

        return path==null
                ?
                0
                :
                path.hashCode();

    }
}