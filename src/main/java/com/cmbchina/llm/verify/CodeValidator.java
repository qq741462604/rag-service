package com.cmbchina.llm.verify;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.skill.Skill;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.ToolInput;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@Component
public class CodeValidator {


    public List<String> validate(
            AgentContext context
    ){


        List<String> errors =
                new ArrayList<>();



        if(context.getObservations()==null){

            return errors;

        }



        List<Observation> observations =
                context.getObservations();


        for(int i=0;i<observations.size();i++){


            Observation o =
                    observations.get(i);


            validateFailedChange(
                    o,
                    i,
                    observations,
                    errors
            );


            if(!o.isSuccess()){

                continue;

            }



            validateFile(
                    o,
                    errors
            );


            validateChangedFile(
                    o,
                    errors
            );


        }


        validateExternalApiContext(
                observations,
                errors
        );


        validateRequiredDependencies(
                context,
                errors
        );


        return errors;

    }


    /**
     * 校验 Skill 要求的 pom.xml 依赖是否已添加。
     *
     * 从匹配 Skill 的 implementation 中提取 <groupId>/<artifactId> 依赖声明，
     * 并检查观察记录中 pom.xml 的快照内容是否包含这些依赖；
     * 若缺失则记录错误，促使 Agent 进入 REPAIR 补充依赖。
     *
     * @param context Agent 上下文（含匹配的 Skills）
     * @param errors  错误列表
     */
    private void validateRequiredDependencies(
            AgentContext context,
            List<String> errors
    ){


        List<Skill> skills =
                context.getSkills();

        if(skills==null
                ||
                skills.isEmpty()){

            return;

        }


        List<String> requiredDeps =
                extractRequiredDependencies(
                        skills
                );

        if(requiredDeps.isEmpty()){

            return;

        }


        String pomContent =
                collectPomContent(
                        context.getObservations()
                );

        if(pomContent==null){

            errors.add(
                    "缺少pom.xml依赖检查：Skill要求添加依赖，但未读取或修改pom.xml"
            );

            return;

        }


        for(String dep: requiredDeps){

            if(!pomContent.contains(dep)){

                errors.add(
                        "pom.xml缺少Skill要求的依赖: "
                                +
                                dep
                );

            }

        }

    }


    /**
     * 从所有匹配 Skill 的 implementation 中提取 <artifactId> 依赖声明。
     *
     * @param skills 匹配的 Skill 列表
     * @return 依赖 artifactId 列表（去重）
     */
    private List<String> extractRequiredDependencies(
            List<Skill> skills
    ){


        List<String> deps =
                new ArrayList<>();


        if(skills==null){

            return deps;

        }


        Pattern artifactPattern =
                Pattern.compile(
                        "<artifactId>\\s*([^<]+?)\\s*</artifactId>"
                );


        for(Skill skill: skills){


            if(skill==null
                    ||
                    skill.getImplementation()==null){

                continue;

            }


            for(String line: skill.getImplementation()){

                if(line==null
                        ||
                        !line.contains("<artifactId>")){

                    continue;

                }


                Matcher matcher =
                        artifactPattern.matcher(
                                line
                        );

                if(matcher.find()
                        &&
                        !deps.contains(
                                matcher.group(1).trim()
                        )){

                    deps.add(
                            matcher.group(1).trim()
                    );

                }

            }

        }


        return deps;

    }
    /**
     * 从观察记录中收集 pom.xml 的最新快照内容。
     *
     * 优先取 write_file/patch_file 的 newText，
     * 否则取 read_file 返回的真实内容。
     *
     * @param observations 观察记录列表
     * @return pom.xml 内容；若从未读取则返回 null
     */
    private String collectPomContent(
            List<Observation> observations
    ){


        if(observations==null
                ||
                observations.isEmpty()){

            return null;

        }


        String latestContent =
                null;


        for(Observation o: observations){

            if(o==null
                    ||
                    !o.isSuccess()){

                continue;

            }


            String path =
                    getInputPath(o);

            if(path==null
                    ||
                    !path.replace("\\","/")
                            .endsWith("pom.xml")){

                continue;

            }


            if(isFileChangeTool(o.getTool())
                    &&
                    o.getInput() instanceof ToolInput){

                ToolInput input =
                        (ToolInput) o.getInput();

                if(input.getNewText()!=null){

                    latestContent =
                            input.getNewText();

                }

                continue;

            }


            if("read_file".equals(o.getTool())
                    &&
                    o.getResult() instanceof FileContent){

                FileContent file =
                        (FileContent) o.getResult();

                latestContent =
                        stripReadLineNumbers(
                                file.getLines()
                        );

            }

        }


        return latestContent;

    }



    private void validateExternalApiContext(
            List<Observation> observations,
            List<String> errors
    ){


        boolean hasApiClient =
                false;


        boolean hasTimeoutConfig =
                false;


        boolean hasTimeoutRestTemplate =
                false;


        boolean hasTarget =
                false;


        boolean targetCallsApiClient =
                false;


        List<String> restTemplateConfigPaths =
                new ArrayList<>();


        List<String> restTemplateBeanNames =
                new ArrayList<>();


        List<FileSnapshot> externalFiles =
                new ArrayList<>();


        String targetBasePackage =
                null;


        for(Observation observation: observations){


            FileSnapshot file =
                    getFileSnapshot(observation);


            if(file==null){

                continue;

            }
            String path =
                    file.getPath()
                            .replace("\\","/");


            String content =
                    file.getContent();


            if(isExternalApiGeneratedFile(path)){

                externalFiles.add(file);

            }


            if(path.endsWith("ApiClient.java")){

                hasApiClient = true;

            }


            if(isExternalApiTargetCandidate(path)){
                if(targetBasePackage==null){
                    targetBasePackage = deriveTargetBasePackage(extractPackage(content));
                }
                if(content.contains("ApiClient") && containsApiClientMethodCall(content)){
                    hasTarget = true;
                    targetCallsApiClient = true;
                }
            }


            if(path.endsWith("ApiConfig.java")
                    &&
                    content.contains("Integer timeout")){

                hasTimeoutConfig = true;

            }


            if(path.endsWith("RestTemplateConfig.java")
                    &&
                    content.contains("getTimeout()")
                    &&
                    content.contains("setConnectTimeout")
                    &&
                    content.contains("setReadTimeout")){

                hasTimeoutRestTemplate = true;

            }


            if(path.endsWith("RestTemplateConfig.java")){

                if(!restTemplateConfigPaths.contains(path)){

                    restTemplateConfigPaths.add(path);

                    String beanName =
                            extractBeanName(content);


                    if(beanName!=null){

                        restTemplateBeanNames.add(beanName);

                    }

                }

            }

        }


        if(hasApiClient
                &&
                hasTimeoutConfig
                &&
                !hasTimeoutRestTemplate){

            errors.add(
                    "外部接口包含timeout配置，但缺少使用ApiConfig.timeout设置超时的RestTemplateConfig"
            );

        }


        if(restTemplateConfigPaths.size()>1){

            errors.add(
                    "外部接口禁止生成多个RestTemplateConfig"
            );

        }


        if(hasDuplicate(
                restTemplateBeanNames
        )){

            errors.add(
                    "外部接口禁止生成重复RestTemplate Bean名称"
            );

        }


        validateExternalApiPackages(
                targetBasePackage,
                externalFiles,
                errors
        );


        if(hasApiClient
                &&
                !hasTarget){

            errors.add(
                    "外部接口已生成ApiClient，但缺少Target最终代码修改"
            );

        }
        if(hasApiClient
                &&
                hasTarget
                &&
                !targetCallsApiClient){

            errors.add(
                    "外部接口已生成ApiClient，但Target缺少实际Client方法调用"
            );

        }

    }


    private boolean isExternalApiGeneratedFile(
            String path
    ){


        return path.endsWith("ApiClient.java")
                ||
                path.endsWith("ApiConfig.java")
                ||
                path.endsWith("RestTemplateConfig.java")
                ||
                path.endsWith("ResponseDTO.java");

    }


    private boolean isExternalApiTargetCandidate(
            String path
    ){


        String normalized =
                path.replace("\\","/");


        return normalized.endsWith(".java")
                &&
                !isExternalApiGeneratedFile(normalized);

    }


    private void validateExternalApiPackages(
            String targetBasePackage,
            List<FileSnapshot> files,
            List<String> errors
    ){


        if(targetBasePackage==null
                ||
                targetBasePackage.length()==0){

            return;

        }


        for(FileSnapshot file: files){


            String expectedPackage =
                    expectedExternalApiPackage(
                            targetBasePackage,
                            file.getPath()
                    );


            if(expectedPackage==null){

                continue;

            }


            String actualPackage =
                    extractPackage(
                            file.getContent()
                    );


            if(!expectedPackage.equals(actualPackage)){

                errors.add(
                        file.getPath()
                                +
                                " 外部接口类包名错误，应为 "
                                +
                                expectedPackage
                );

            }


            String expectedPathPart =
                    expectedPackage.replace(
                            ".",
                            "/"
                    );


            if(!file.getPath()
                    .replace("\\","/")
                    .contains(expectedPathPart)){

                errors.add(
                        file.getPath()
                                +
                                " 外部接口类路径错误，应位于 "
                                +
                                expectedPathPart
                );

            }

        }

    }
    private String expectedExternalApiPackage(
            String targetBasePackage,
            String path
    ){


        if(path.endsWith("ApiClient.java")){

            return targetBasePackage + ".client";

        }


        if(path.endsWith("ApiConfig.java")
                ||
                path.endsWith("RestTemplateConfig.java")){

            return targetBasePackage + ".config";

        }


        if(path.endsWith("ResponseDTO.java")){

            return targetBasePackage + ".dto";

        }


        return null;

    }


    private String deriveTargetBasePackage(
            String targetPackage
    ){


        if(targetPackage==null){

            return null;

        }


        if(targetPackage.endsWith(".extension")){

            return targetPackage.substring(
                    0,
                    targetPackage.length()
                            - ".extension".length()
            );

        }


        return targetPackage;

    }


    private String extractPackage(
            String content
    ){


        Pattern pattern =
                Pattern.compile(
                        "package\\s+([A-Za-z0-9_.]+)\\s*;"
                );


        Matcher matcher =
                pattern.matcher(content);


        if(!matcher.find()){

            return null;

        }


        return matcher.group(1);

    }


    private boolean hasDuplicate(
            List<String> values
    ){


        for(int i=0;i<values.size();i++){


            for(int j=i+1;j<values.size();j++){


                if(values.get(i)
                        .equals(values.get(j))){

                    return true;

                }

            }

        }


        return false;

    }
    private String extractBeanName(
            String content
    ){


        String marker =
                "@Bean(\"";


        int start =
                content.indexOf(marker);


        if(start<0){

            return null;

        }


        int nameStart =
                start + marker.length();


        int nameEnd =
                content.indexOf(
                        "\"",
                        nameStart
                );


        if(nameEnd<0){

            return null;

        }


        return content.substring(
                nameStart,
                nameEnd
        );

    }


    private void validateFailedChange(
            Observation observation,
            int index,
            List<Observation> observations,
            List<String> errors
    ){


        if(observation.isSuccess()){

            return;

        }


        if(!isFileChangeTool(
                observation.getTool()
        )){

            return;

        }


        if(hasLaterSuccessfulChange(
                observation,
                index,
                observations
        )){

            return;

        }


        errors.add(
                "文件修改失败 tool="
                        +
                        observation.getTool()
                        +
                        " path="
                        +
                        getInputPath(observation)
                        +
                        " error="
                        +
                        observation.getError()
        );

    }


    private boolean hasLaterSuccessfulChange(
            Observation failed,
            int index,
            List<Observation> observations
    ){


        String failedPath =
                getInputPath(failed);


        if(failedPath==null){

            return false;

        }


        for(int i=index+1;i<observations.size();i++){


            Observation current =
                    observations.get(i);


            if(!current.isSuccess()
                    ||
                    !isFileChangeTool(current.getTool())){

                continue;

            }


            if(failedPath.equals(
                    getInputPath(current)
            )){

                return true;

            }

        }


        return false;

    }

    private boolean isFileChangeTool(
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


    private String getInputPath(
            Observation observation
    ){


        if(observation.getInput()
                instanceof ToolInput){

            return ((ToolInput) observation.getInput())
                    .getPath();

        }


        return null;

    }


    private void validateChangedFile(
            Observation observation,
            List<String> errors
    ){


        if(!isFileChangeTool(
                observation.getTool()
        )){

            return;

        }


        if(!(observation.getInput()
                instanceof ToolInput)){

            return;

        }


        ToolInput input =
                (ToolInput) observation.getInput();


        if(input.getPath()==null
                ||
                input.getNewText()==null){

            return;

        }


        validateExternalApiFile(
                input.getPath(),
                input.getNewText(),
                errors
        );

    }
    private FileSnapshot getFileSnapshot(
            Observation observation
    ){


        if(!observation.isSuccess()){

            return null;

        }


        if("read_file".equals(observation.getTool())
                &&
                observation.getResult()
                        instanceof FileContent){


            FileContent file =
                    (FileContent) observation.getResult();


            return new FileSnapshot(
                    file.getPath(),
                    stripReadLineNumbers(file.getLines())
            );

        }


        if(isFileChangeTool(
                observation.getTool()
        )
                &&
                observation.getInput()
                        instanceof ToolInput){


            ToolInput input =
                    (ToolInput) observation.getInput();


            if(input.getPath()==null
                    ||
                    input.getNewText()==null){

                return null;

            }


            return new FileSnapshot(
                    input.getPath(),
                    input.getNewText()
            );

        }


        return null;

    }


    private boolean containsApiClientMethodCall(
            String content
    ){


        Pattern pattern =
                Pattern.compile(
                        "[A-Za-z0-9_]+ApiClient\\s*\\.\\s*[A-Za-z0-9_]+\\s*\\("
                );


        return pattern.matcher(content)
                .find();

    }


    private String stripReadLineNumbers(
            List<String> lines
    ){


        if(lines==null
                ||
                lines.isEmpty()){

            return "";

        }


        List<String> content =
                new ArrayList<>();


        for(String line: lines){

            content.add(
                    line.replaceFirst(
                            "^\\d+: ?",
                            ""
                    )
            );

        }


        return String.join(
                "\n",
                content
        );

    }
    private static class FileSnapshot {


        private final String path;


        private final String content;


        private FileSnapshot(
                String path,
                String content
        ){

            this.path = path;
            this.content = content;

        }


        private String getPath(){

            return path;

        }


        private String getContent(){

            return content;

        }

    }





    private void validateFile(
            Observation observation,
            List<String> errors
    ){


        if(!"read_file".equals(
                observation.getTool()
        )){


            return;

        }



        if(!(observation.getResult()
                instanceof FileContent)){


            return;

        }



        FileContent file =
                (FileContent)
                        observation.getResult();



        String path =
                file.getPath();



        String content =
                stripReadLineNumbers(
                        file.getLines()
                );


        validateJavaSyntax(
                path,
                content,
                errors
        );


        if(path.endsWith("Controller.java")){


            if(!content.contains(
                    "@RestController"
            )){


                errors.add(
                        path
                                +
                                " Controller缺少@RestController"
                );

            }


        }





        if(path.endsWith("Service.java")){


            if(!content.contains(
                    "@Service"
            )){


                errors.add(
                        path
                                +
                                " Service缺少@Service"
                );

            }


        }


        validateExternalApiFile(
                path,
                content,
                errors
        );


    }
    private void validateJavaSyntax(
            String path,
            String content,
            List<String> errors
    ){


        if(path==null
                ||
                content==null
                ||
                !path.replace("\\","/")
                        .endsWith(".java")){

            return;

        }


        if(!looksLikeCompleteJavaSource(content)){

            return;

        }


        try{

            ParserConfiguration configuration =
                    new ParserConfiguration()
                            .setLanguageLevel(
                                    ParserConfiguration.LanguageLevel.JAVA_11
                            );

            StaticJavaParser.setConfiguration(
                    configuration
            );

            StaticJavaParser.parse(
                    content
            );

        }catch(Exception e){

            errors.add(
                    path
                            +
                            " Java语法解析失败:"
                            +
                            e.getMessage()
            );

        }

    }


    private boolean looksLikeCompleteJavaSource(
            String content
    ){


        String text =
                content.trim();


        return text.startsWith("package ")
                &&
                text.contains(" class ")
                &&
                text.endsWith("}");

    }


    private void validateExternalApiFile(
            String path,
            String content,
            List<String> errors
    ){


        String normalizedPath =
                path.replace("\\","/");


        if(normalizedPath.endsWith("ApiClient.java")){

            validateApiClient(
                    path,
                    content,
                    errors
            );

            return;

        }


        if(normalizedPath.endsWith("ApiConfig.java")){

            validateApiConfig(
                    path,
                    content,
                    errors
            );

            return;

        }


        if(normalizedPath.endsWith("RestTemplateConfig.java")){

            validateRestTemplateConfig(
                    path,
                    content,
                    errors
            );

            return;

        }
        if(isExternalApiTargetCandidate(normalizedPath)
                &&
                content.contains("ApiClient")){

            validateExternalApiTarget(
                    path,
                    content,
                    errors
            );

        }

    }


    private void validateApiClient(
            String path,
            String content,
            List<String> errors
    ){


        if(!content.contains("@Component")){

            errors.add(
                    path
                            +
                            " ApiClient缺少@Component"
            );

        }


        if(content.contains("@Autowired")){

            errors.add(
                    path
                            +
                            " ApiClient禁止使用@Autowired字段注入"
            );

        }


        if(content.contains("@Value")){

            errors.add(
                    path
                            +
                            " ApiClient禁止使用@Value读取配置"
            );

        }


        if(content.contains("new RestTemplate")){

            errors.add(
                    path
                            +
                            " ApiClient禁止new RestTemplate"
            );

        }


        if(content.contains("http://")
                ||
                content.contains("https://")){

            errors.add(
                    path
                            +
                            " ApiClient禁止硬编码接口地址"
            );

        }


        if(!content.contains("final RestTemplate restTemplate")){

            errors.add(
                    path
                            +
                            " ApiClient必须使用final RestTemplate字段"
            );

        }


        if(!content.contains("final ")
                ||
                !content.contains("ApiConfig config")){

            errors.add(
                    path
                            +
                            " ApiClient必须使用final XxxApiConfig config字段"
            );

        }
        if(!content.contains("config.getUrl()")){

            errors.add(
                    path
                            +
                            " ApiClient必须从ApiConfig读取url"
            );

        }


        if(!content.contains("config.getMethod()")){

            errors.add(
                    path
                            +
                            " ApiClient必须从ApiConfig读取method"
            );

        }


        validateRequiredImport(
                path,
                content,
                "ResponseEntity",
                "import org.springframework.http.ResponseEntity;",
                errors
        );


        validateRequiredImport(
                path,
                content,
                "HttpMethod",
                "import org.springframework.http.HttpMethod;",
                errors
        );


        validateRequiredImport(
                path,
                content,
                "RestTemplate",
                "import org.springframework.web.client.RestTemplate;",
                errors
        );


        validateRequiredImport(
                path,
                content,
                "UriComponentsBuilder",
                "import org.springframework.web.util.UriComponentsBuilder;",
                errors
        );


        validateRequiredImport(
                path,
                content,
                "URI",
                "import java.net.URI;",
                errors
        );

    }


    private void validateRequiredImport(
            String path,
            String content,
            String typeName,
            String importLine,
            List<String> errors
    ){


        String fullyQualifiedName =
                importLine.replace("import ","")
                        .replace(";","");


        if(content.contains(typeName)
                &&
                !content.contains(fullyQualifiedName)
                &&
                !content.contains(importLine)){

            errors.add(
                    path
                            +
                            " 使用"
                            +
                            typeName
                            +
                            "但缺少import: "
                            +
                            importLine
            );

        }

    }
    private void validateApiConfig(
            String path,
            String content,
            List<String> errors
    ){


        if(!content.contains("@Component")){

            errors.add(
                    path
                            +
                            " ApiConfig缺少@Component"
            );

        }


        if(!content.contains("@ConfigurationProperties")){

            errors.add(
                    path
                            +
                            " ApiConfig缺少@ConfigurationProperties"
            );

        }


        if(!content.contains("String url")){

            errors.add(
                    path
                            +
                            " ApiConfig缺少url配置"
            );

        }


        if(!content.contains("Integer timeout")){

            errors.add(
                    path
                            +
                            " ApiConfig缺少timeout配置"
            );

        }


        if(!content.contains("String method")){

            errors.add(
                    path
                            +
                            " ApiConfig缺少method配置"
            );

        }


        if(content.contains("RestTemplate")
                ||
                content.contains("@Bean")){

            errors.add(
                    path
                            +
                            " ApiConfig禁止保存RestTemplate或声明@Bean"
            );

        }

    }


    private void validateRestTemplateConfig(
            String path,
            String content,
            List<String> errors
    ){


        if(!content.contains("@Configuration")){

            errors.add(
                    path
                            +
                            " RestTemplateConfig缺少@Configuration"
            );

        }


        if(!content.contains("@Bean")){

            errors.add(
                    path
                            +
                            " RestTemplateConfig缺少@Bean"
            );

        }


        if(!content.contains("RestTemplate")){

            errors.add(
                    path
                            +
                            " RestTemplateConfig必须创建RestTemplate"
            );

        }
        if(!content.contains("getTimeout()")){

            errors.add(
                    path
                            +
                            " RestTemplateConfig必须使用ApiConfig.timeout"
            );

        }


        if(!content.contains("setConnectTimeout")
                ||
                !content.contains("setReadTimeout")){

            errors.add(
                    path
                            +
                            " RestTemplateConfig必须设置connectTimeout和readTimeout"
            );

        }

    }


    private void validateExternalApiTarget(
            String path,
            String content,
            List<String> errors
    ){


        if(!content.contains("ApiClient")){

            return;

        }


        if(content.contains("@Component")){

            errors.add(
                    path
                            +
                            " Target禁止新增@Component改变组件模型"
            );

        }


        if(content.contains("@RequiredArgsConstructor")){

            errors.add(
                    path
                            +
                            " Target禁止新增@RequiredArgsConstructor改变生命周期"
            );

        }


        if(content.contains("@Autowired")){

            errors.add(
                    path
                            +
                            " Target禁止使用@Autowired注入Client"
            );

        }


        validateTargetApiClientInitialization(
                path,
                content,
                errors
        );


        if(content.contains("eventFields.put(")){

            errors.add(
                    path
                            +
                            " Target写回结果必须使用eventData.putDataAndOriginData"
            );

        }
        if(!content.contains("putDataAndOriginData(")){

            errors.add(
                    path
                            +
                            " Target缺少eventData.putDataAndOriginData调用"
            );

        }


        if(content.contains("RestTemplate")
                ||
                content.contains("HttpURLConnection")
                ||
                content.contains("new URL")
                ||
                content.contains("http://")
                ||
                content.contains("https://")){

            errors.add(
                    path
                            +
                            " Target禁止包含HTTP调用细节"
            );

        }

    }


    private void validateTargetApiClientInitialization(
            String path,
            String content,
            List<String> errors
    ){


        Pattern pattern =
                Pattern.compile(
                        "private\\s+(?!final\\s+)[A-Za-z0-9_]+ApiClient\\s+([A-Za-z0-9_]+)\\s*;"
                );


        Matcher matcher =
                pattern.matcher(content);


        while(matcher.find()){


            String fieldName =
                    matcher.group(1);


            if(!content.contains(
                    fieldName + " ="
            )){

                errors.add(
                        path
                                +
                                " Target中的ApiClient字段缺少初始化来源"
                );

            }


            if(containsNullGuard(
                    content,
                    fieldName
            )){

                errors.add(
                        path
                                +
                                " Target禁止使用ApiClient非空判断绕过外部接口调用"
                );

            }

        }

    }
    private boolean containsNullGuard(
            String content,
            String fieldName
    ){


        Pattern pattern =
                Pattern.compile(
                        "if\\s*\\(\\s*(this\\.)?"
                                +
                                Pattern.quote(fieldName)
                                +
                                "\\s*!=\\s*null\\s*\\)"
                );


        return pattern.matcher(content)
                .find();

    }


}