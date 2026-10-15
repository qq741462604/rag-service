package com.cmbchina.llm.tool;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.project.ProjectAnalyzer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class ProjectAnalyzeTool extends BaseTool {


    private final ProjectAnalyzer analyzer;


    @Override
    public String name() {

        return "analyze_project";

    }


    @Override
    public String description() {

        return "analyze project structure";

    }


    @Override
    public Object execute(
            Object input,
            AgentContext context
    ) {


        ToolInput toolInput = (ToolInput)input;


        return analyzer.analyze(toolInput.getPath());


    }


}