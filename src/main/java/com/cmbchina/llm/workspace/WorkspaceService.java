package com.cmbchina.llm.workspace;


import com.cmbchina.llm.config.WorkspaceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


@Service
@RequiredArgsConstructor
public class WorkspaceService {


    private final WorkspaceProperties properties;




    public File getRoot(){


        return new File(
                properties.getRoot()
        );

    }






    public File resolve(
            String path
    ){


        if(path==null
                ||
                path.trim().isEmpty()){

            throw new IllegalArgumentException(
                    "workspace path empty"
            );

        }


        try{

            File root =
                    getRoot()
                            .getCanonicalFile();


            File file =
                    new File(path);


            File target =
                    file.isAbsolute()
                            ?
                            file.getCanonicalFile()
                            :
                            new File(
                                    root,
                                    path
                            ).getCanonicalFile();


            if(!isUnderRoot(
                    root,
                    target
            )){

                throw new IllegalArgumentException(
                        "path outside workspace: "
                                +
                                target.getAbsolutePath()
                );

            }


            return target;


        }catch(IllegalArgumentException e){

            throw e;

        }catch(Exception e){

            throw new RuntimeException(
                    "resolve workspace path failed:"
                            +
                            path,
                    e
            );

        }

    }


    private boolean isUnderRoot(
            File root,
            File target
    ) throws Exception {


        String rootPath =
                root.getCanonicalPath();


        String targetPath =
                target.getCanonicalPath();


        return targetPath.equals(rootPath)
                ||
                targetPath.startsWith(
                        rootPath
                                +
                                File.separator
                );


    }







/**
 * 解析文件路径，并在目标不存在时按文件名做唯一匹配纠正。
 *
 * <p>当 LLM 生成的 patch_file / read_file 路径存在包路径偏差（例如漏掉某个
 * 包目录段），直接解析会得到不存在的文件。此方法在路径对应文件不存在时，
 * 遍历 workspace 查找同名文件（如 {@code DataPrepEventHandler.java}）：
 * 若恰好存在唯一一个同名文件，则返回该文件的真实路径，从而保证 patch 能落到
 * 正确的目标文件上；若存在多个同名文件或找不到，则返回原解析结果（不存在的文件）。</p >
 *
 * @param path 待解析的路径（相对或绝对）
 * @return 解析后的文件；若路径不存在但存在唯一同名文件，返回该同名文件
 */
public File resolveExistingFile(
        String path
){

    File target =
            resolve(
                    path
            );

    if(target.exists()){

        return target;

    }

    String fileName =
            target.getName();

    if(fileName == null
            ||
            fileName.trim().isEmpty()){

        return target;

    }

    List<File> matches =
            new ArrayList<>();

    collectByName(
            getRoot(),
            fileName,
            matches
    );

    if(matches.size() == 1){

        return matches.get(0);

    }

    return target;

}


    /**
     * 递归收集 workspace 下与指定文件名完全一致的普通文件。
     */
    private void collectByName(
            File dir,
            String fileName,
            List<File> matches
    ){

        if(dir == null
                || !dir.isDirectory()){

            return;

        }

        File[] children =
                dir.listFiles();

        if(children == null){

            return;

        }

        for(File child : children){

            if(child.isDirectory()){

                collectByName(
                        child,
                        fileName,
                        matches
                );

            }else if(child.isFile()
                    &&
                    child.getName().equals(fileName)){

                matches.add(child);

            }

        }

    }
    /**
     * 获取相对于workspace的路径
     *
     * 例如:
     *
     * D:/ideaProject/test/src/main/java/A.java
     *
     * 返回:
     *
     * src/main/java/A.java
     *
     */
    public String relativePath(
            File file
    ){


        try{


            Path root =
                    getRoot()
                            .toPath()
                            .toAbsolutePath()
                            .normalize();



            Path target =
                    file.toPath()
                            .toAbsolutePath()
                            .normalize();



            return root
                    .relativize(target)
                    .toString()
                    .replace("\\","/");



        }catch(Exception e){


            throw new RuntimeException(
                    "convert relative path failed:"
                            +
                            file.getAbsolutePath(),
                    e
            );


        }


    }


}