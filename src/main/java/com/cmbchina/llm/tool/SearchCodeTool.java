package com.cmbchina.llm.tool;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.workspace.WorkspaceIgnoreMatcher;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


@Component
@Slf4j
@RequiredArgsConstructor
public class SearchCodeTool extends BaseTool {


    private final WorkspaceService workspaceService;

    private final WorkspaceIgnoreMatcher matcher;

    @Override
    public String name() {

        return "search_code";

    }



    @Override
    public String description() {

        return "搜索项目代码内容，返回匹配文件、行号以及上下文";

    }




    @Override
    public void validate(
            Object input
    ){


        ToolInput toolInput =
                (ToolInput) input;



        if(toolInput.getKeyword()==null
                ||
                toolInput.getKeyword().trim().isEmpty()){


            throw new IllegalArgumentException(
                    "search_code keyword is empty"
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



        String keyword =
                toolInput.getKeyword();



        String path =
                toolInput.getPath();



        if(path==null
                ||
                path.trim().isEmpty()){


            path=".";

        }





        File root =
                workspaceService.resolve(
                        path
                );



        if(!root.exists()){

            // 当 LLM 指定的搜索路径不存在时，不直接报错失败，
            // 而是回退到 workspace 根目录进行全量搜索。
            // 这样避免 LLM 因路径错误陷入"搜索失败→重试→再失败"的死循环，
            // 最终耗尽 MAX_ITERATION 而无任何代码落盘。
            log.warn(
                    "search_code path not exists: {}, fallback to workspace root",
                    root.getAbsolutePath()
            );
            root =
                    workspaceService.resolve(
                            "."
                    );

            if(!root.exists()){

                throw new RuntimeException(
                        "workspace root not exists:"
                                +
                                root.getAbsolutePath()
                );

            }

        }






        List<SearchMatch> result =
                new ArrayList<>();



        scan(
                root,
                keyword,
                result
        );




        return result;

    }







    private void scan(
            File file,
            String keyword,
            List<SearchMatch> result
    ){



        if(file==null){

            return;

        }



        if(matcher.match(file)){

            return;

        }




        if(file.isDirectory()){


            File[] files =
                    file.listFiles();



            if(files!=null){


                for(File child:files){


                    scan(
                            child,
                            keyword,
                            result
                    );


                }

            }



            return;

        }





        searchFile(
                file,
                keyword,
                result
        );


    }


    private void searchFile(
            File file,
            String keyword,
            List<SearchMatch> result
    ){



        BufferedReader reader=null;



        try{


            List<String> lines =
                    new ArrayList<>();


            reader =
                    Files.newBufferedReader(
                            file.toPath(),
                            StandardCharsets.UTF_8
                    );



            String line;



            while(
                    (line=reader.readLine())
                            !=null
            ){


                lines.add(line);


            }



            for(int i=0;i<lines.size();i++){


                String current =
                        lines.get(i);

                Pattern pattern =
                        Pattern.compile(
                                keyword
                        );
                if(pattern.matcher(current).find()
                        && !isImportLine(current)
                        && !isPackageLine(current)
                ){
//                if(current.contains(keyword)
//                        && !isImportLine(current)
//                        && !isPackageLine(current)
//                ){



                    SearchMatch match =
                            SearchMatch.builder()
                                    .path(
                                            workspaceService.relativePath(file)
                                    )
                                    .lineNumber(
                                            i+1
                                    )
                                    .content(
                                            current.trim()
                                    )
                                    .context(
                                            buildContext(
                                                    lines,
                                                    i
                                            )
                                    )
                                    .matchType(
                                            resolveMatchType(
                                                    current,
                                                    keyword
                                            )
                                    )
                                    .build();




                    result.add(match);



                    // 一个文件只返回一次
                    break;

                }


            }



        }catch(Exception e){


            // 忽略无法读取文件


        }finally{


            try{

                if(reader!=null){

                    reader.close();

                }

            }catch(Exception ignored){

            }


        }



    }
    private boolean isImportLine(
            String line
    ){

        String text =
                line.trim();


        return text.startsWith("import ");

    }



    private boolean isPackageLine(
            String line
    ){

        String text =
                line.trim();


        return text.startsWith("package ");

    }


    /**
     * 判断命中行是"类定义"还是"引用"。
     *
     * <p>规则：若命中行本身包含 {@code class <keyword>}、{@code interface <keyword>}、
     * {@code enum <keyword>} 或 {@code @interface <keyword>} 的声明，则为 definition；
     * 否则（如 {@code new DataPrepEventHandler()}、作为类型使用等）为 reference。</p >
     *
     * @param line    命中行内容
     * @param keyword 搜索关键字（类名）
     * @return definition 或 reference
     */
    private String resolveMatchType(
            String line,
            String keyword
    ){

        String text =
                line.trim();

        String kw =
                keyword.trim();

        // 处理 keyword 为通配符类名（如 DataPrepEventHandler）的情况
        // 类声明形如：class DataPrepEventHandler、public class DataPrepEventHandler 等
        if(text.matches(
                ".*\\b(class|interface|enum|@interface)\\s+"
                        +
                        Pattern.quote(kw)
                        +
                        "\\b.*"
        )){

            return "definition";

        }


        // 其余情况视为引用
        return "reference";

    }
    private List<String> buildContext(
            List<String> lines,
            int index
    ){

        List<String> context =
                new ArrayList<>();


        int start =
                Math.max(
                        0,
                        index-5
                );


        int end =
                Math.min(
                        lines.size(),
                        index+6
                );


        for(int i=start;i<end;i++){


            String line =
                    lines.get(i);



            if(line.trim().startsWith("import ")
                    ||
                    line.trim().startsWith("package ")){

                continue;

            }


            context.add(line);


        }


        return context;

    }











}