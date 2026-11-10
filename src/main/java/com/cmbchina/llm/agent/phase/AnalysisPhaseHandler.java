package com.cmbchina.llm.agent.phase;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.AgentPhase;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.skill.Skill;
import com.cmbchina.llm.tool.FileContent;
import com.cmbchina.llm.tool.SearchMatch;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;


@Component
@RequiredArgsConstructor
@Slf4j
public class AnalysisPhaseHandler implements AgentPhaseHandler {

    private final WorkspaceService workspaceService;


    @Override
    public AgentPhase phase(){

        return AgentPhase.ANALYSIS;

    }



    @Override
    public AgentPhase next(
            AgentContext context
    ){


        /*
         *
         * 判断是否已经收集足够信息
         *
         */


        if(context.getObservations()==null){

            return AgentPhase.ANALYSIS;

        }



        Set<String> targets = new java.util.LinkedHashSet<>();
        if (context.getSkills() != null) {
            for (Skill skill : context.getSkills()) {
                if (skill.getTarget() != null && skill.getTarget().getClassName() != null
                        && !skill.getTarget().getClassName().trim().isEmpty()) {
                    targets.add(skill.getTarget().getClassName().trim());
                }
            }
        }
        if (targets.isEmpty()) {
            return analyzeTarget(context, null);
        }
        for (String target : targets) {
            if (analyzeTarget(context, target) != AgentPhase.CODING) {
                return AgentPhase.ANALYSIS;
            }
        }
        return AgentPhase.CODING;
    }

    private AgentPhase analyzeTarget(AgentContext context, String targetClassName) {
        Set<File> searchedTargets = new HashSet<>();
        for (Observation observation : context.getObservations()) {
            if (!observation.isSuccess() || !"search_code".equals(observation.getTool())
                    || !(observation.getResult() instanceof List)) {
                continue;
            }
            List<?> matches = (List<?>) observation.getResult();
            if (targetClassName == null && !matches.isEmpty()) {
                return AgentPhase.CODING;
            }
            for (Object match : matches) {
                if (!(match instanceof SearchMatch)) {
                    continue;
                }
                String path = ((SearchMatch) match).getPath();
                if (path != null && (targetClassName + ".java").equalsIgnoreCase(new File(path).getName())) {
                    File target = resolveTarget(path);
                    if (target != null) {
                        searchedTargets.add(target);
                    }
                }
            }
        }

        // 已知真实路径时可以直接读取；search_code 是定位方式，不能成为阶段推进的前置条件。
        if (targetClassName != null) {
            Pattern packagePattern = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");
            Pattern typePattern = Pattern.compile("\\b(class|interface|enum)\\s+"
                    + Pattern.quote(targetClassName) + "\\b");
            for (Observation observation : context.getObservations()) {
                if (!observation.isSuccess() || !"read_file".equals(observation.getTool())
                        || !(observation.getResult() instanceof FileContent)) {
                    continue;
                }
                FileContent content = (FileContent) observation.getResult();
                File target = resolveTarget(content.getPath());
                if (content.getLines() == null || target == null
                        || !(targetClassName + ".java").equalsIgnoreCase(target.getName())) {
                    continue;
                }
                searchedTargets.add(target);
                String source = String.join("\n", content.getLines()).replaceAll("(?m)^\\d+: ?", "");
                if (packagePattern.matcher(source).find() && typePattern.matcher(source).find()) {
                    return AgentPhase.CODING;
                }
            }
        }
        log.info("analysis pending targetClass={} located={} awaiting successful target source read",
                targetClassName, !searchedTargets.isEmpty());

        return AgentPhase.ANALYSIS;

    }

    private File resolveTarget(String path) {
        if (path == null) {
            return null;
        }
        try {
            return workspaceService.resolve(path).getCanonicalFile();
        } catch (Exception e) {
            return null;
        }
    }

}
