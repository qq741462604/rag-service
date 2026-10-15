package com.cmbchina.llm.tool;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.config.AgentHomeService;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;



@Component
@RequiredArgsConstructor
public class ReadFileTool extends BaseTool {


    private final WorkspaceService workspaceService;

    private final AgentHomeService agentHomeService;



    private static final int DEFAULT_LIMIT = 200;



    @Override
    public String name() {

        return "read_file";

    }



    @Override
    public String description() {

        return "读取项目文件内容";

    }




    @Override
    public void validate(
            Object input
    ){


        ToolInput toolInput =
                (ToolInput) input;



        if(toolInput.getPath()==null
                ||
                toolInput.getPath().trim().isEmpty()){


            throw new IllegalArgumentException(
                    "read_file path empty"
            );

        }


    }





    @Override
    public Object execute(
            Object input,
            AgentContext context
    ) throws Exception {


        logExecute(input);



        ToolInput toolInput =
                (ToolInput) input;



        File file =
                resolveFile(
                        toolInput.getPath()
                );



        if(!file.exists()
                ||
                !file.isFile()){


            throw new RuntimeException(
                    "file not found:"
                            +
                            file.getAbsolutePath()
            );

        }



        int startLine =
                toolInput.getStartLine()==null
                        ?
                        1
                        :
                        toolInput.getStartLine();



        int endLine =
                toolInput.getEndLine()==null
                        ?
                        startLine + DEFAULT_LIMIT
                        :
                        toolInput.getEndLine();



        return read(
                file,
                startLine,
                endLine
        );


    }






    private File resolveFile(
            String path
    ){


        /*
         * 优先解析到 workspace（目标项目）目录。
         *
         * 使用 resolveExistingFile：当 LLM 生成的路径存在包路径偏差
         * （例如把 com/cmbchina/plugin/extension/ 误写成 .../handler/）时，
         * 会按文件名在 workspace 内做唯一匹配纠正，避免 read_file 因
         * 路径不精确而报 file not found，从而能真正读取到目标文件。
         *
         * 注意：workspace 解析可能抛出 IllegalArgumentException
         * （例如模型输出了指向 agent home docs 的绝对路径，该路径不在
         * workspace 内）。此时不能阻断流程，应视为"workspace 未命中"，
         * 继续回退到 agent home 的 docs 参考目录解析。
         */
        File file = null;
        try {
            file = workspaceService.resolveExistingFile(path);
        } catch (IllegalArgumentException ignored) {
            // 路径不在 workspace 内：跳过 workspace 解析，直接走 docs 回退
        }

        if(file != null
                &&
                (file.exists()
                        ||
                        file.isFile())){


            return file;

        }


        /*
         * workspace（目标项目）下不存在时，
         * 回退到 agent home 项目的 docs 参考文档目录（只读参考）。
         *
         * 例如：read_file(docs/predict-call-template.md)
         * 目标项目下没有该文件时，读取 agent home 项目的 docs/predict-call-template.md。
         *
         * 兼容两种路径形式：
         * - 相对路径：docs/predict-call-template.md
         * - 绝对路径：D:\FlinkTest\demo\...\docs\predict-call-template.md
         *   此时从原路径中提取文件名，到 agent home 的 docs 目录下查找。
         */
        File reference =
                resolveFromAgentHomeDocs(
                        path
                );


        if(reference.exists()
                ||
                reference.isFile()){


            return reference;

        }


        return file != null ? file : reference;

    }
    /**
     * 尝试从 agent home 项目的 docs 参考文档目录解析文件。
     *
     * <p>先尝试用原始路径拼接 home 目录；如果该路径不是相对 docs 路径
     * （例如模型输出了绝对路径），则提取文件名到 home/docs 下查找。</p >
     *
     * @param path 原始路径（相对或绝对）
     * @return 解析后的文件（可能不存在）
     */
    private File resolveFromAgentHomeDocs(
            String path
    ){


        if(path==null
                ||
                path.trim().isEmpty()){


            return agentHomeService.resolve(path);

        }


        String trimmed = path.trim();


        /*
         * 相对路径（docs/ 或 docs\ 开头）：直接用原路径拼接 home 目录。
         */
        String normalized = trimmed.replace('\\','/');
        if(normalized.startsWith("docs/")){


            return agentHomeService.resolve(trimmed);

        }


        /*
         * 绝对路径或其它形式：提取 docs/ 之后的完整子路径（含子目录），到 home 的 docs 目录下查找。
         * 例如 D:\...\docs\predict-call-template.md -> home/docs/predict-call-template.md
         * 例如 D:\...\docs\predict-call-template-parts\00-overview.md -> home/docs/predict-call-template-parts/00-overview.md
         */
        String normalizedPath = trimmed.replace('\\', '/');

        // 尝试提取 docs/ 之后的完整相对路径（保留子目录结构）
        int docsIdx = normalizedPath.indexOf("/docs/");
        if (docsIdx >= 0) {
            String subPath = normalizedPath.substring(docsIdx + 1); // 去掉前导 "/"，得到 "docs/..."
            return agentHomeService.resolve(subPath);
        }

        // 路径中没有 docs/ 段，退化为只提取文件名
        String fileName =
                new File(trimmed)
                        .getName();


        if(fileName.isEmpty()){


            return agentHomeService.resolve(trimmed);

        }


        return agentHomeService.resolve(
                "docs"
                        +
                        File.separator
                        +
                        fileName
        );

    }





    private FileContent read(
            File file,
            int startLine,
            int endLine
    ){


        BufferedReader reader=null;


        List<String> result =
                new ArrayList<>();



        try{


            reader =
                    Files.newBufferedReader(
                            file.toPath(),
                            StandardCharsets.UTF_8
                    );



            String line;


            int current=1;



            while(
                    (line=reader.readLine())
                            !=null
            ){



                if(current>=startLine
                        &&
                        current<=endLine){


                    result.add(
                            current
                                    +
                                    ": "
                                    +
                                    line
                    );


                }



                if(current>endLine){

                    break;

                }


                current++;


            }




            return FileContent.builder()
                    .path(
                            file.getCanonicalPath()
                    )
                    .startLine(
                            startLine
                    )
                    .endLine(
                            endLine
                    )
                    .lines(
                            result
                    )
                    .build();



        }catch(Exception e){


            throw new RuntimeException(
                    e
            );


        }finally{


            try{

                if(reader!=null){

                    reader.close();

                }

            }catch(Exception ignored){

            }

        }


    }


}
