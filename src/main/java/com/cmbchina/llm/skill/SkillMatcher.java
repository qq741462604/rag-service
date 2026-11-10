package com.cmbchina.llm.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SkillMatcher {

    private final SkillLoader loader;
    private List<Skill> cache;

    public boolean hasBusinessMatch(String task) {
        if (task == null || task.trim().isEmpty()) {
            return false;
        }
        if (cache == null) {
            cache = loader.load();
        }

        for (Skill skill : cache) {
            if (skill.isAlwaysApply()) {
                continue;
            }
            if (matches(task, skill)) {
                return true;
            }
        }
        return false;
    }

    public List<Skill> match(String task) {
        if (cache == null) {
            cache = loader.load();
        }

        Set<Skill> result = new LinkedHashSet<>();
        for (Skill skill : cache) {
            if (skill.isAlwaysApply()) {
                result.add(skill);
            }
        }
        if (task != null) {
            for (Skill skill : cache) {
                if (skill.isAlwaysApply()) {
                    continue;
                }
                if (matches(task, skill)) {
                    result.add(skill);
                }
            }
        }

        List<Skill> skills = new ArrayList<>(result);
        // 仅返回已有评分不等于新增模型调用；组合需求明确要求调用时保留两个入口。
        String request = task == null ? "" : task.toLowerCase(java.util.Locale.ROOT);
        boolean returnScore = skills.stream().anyMatch(skill -> "rtd-results".equals(skill.getName()))
                && java.util.Arrays.asList("返回", "rtdresults", "scoreanalysiseventhandler", "procvars", "rtqvars")
                .stream().anyMatch(request::contains);
        boolean invokesModel = java.util.Arrays.asList("模型预测", "预测接口", "模型调用", "外调模型", "调用模型",
                "模型平台接口", "ahiinvoke", "ahihttpinvokerhandler", "httpclientutil", "buildrequestbody")
                .stream().anyMatch(request::contains);
        boolean explicitReturn = java.util.Arrays.asList("rtdresults", "scoreanalysiseventhandler", "评分返回",
                "返回数据加工", "rtd返回", "rtd结果", "返回给", "返回结果中", "返回结果里", "添加到返回",
                "加入返回", "返回已有", "返回模型评分").stream().anyMatch(request::contains);
        if (invokesModel && !explicitReturn) {
            // 模型响应/评分写回 DataPrep 不意味着改造 RTD 返回入口；显式组合需求仍保留。
            skills.removeIf(skill -> "rtd-results".equals(skill.getName()));
        }
        if (returnScore && !invokesModel) {
            skills.removeIf(skill -> "predict-api".equals(skill.getName()));
            // “返回模型评分接口”里的泛词“接口”不能引入无关 HTTP 客户端链。
            boolean invokesExternal = java.util.Arrays.asList("外调", "resttemplate", "http", "rest",
                    "调用接口", "接口调用", "调用外部", "timeout").stream().anyMatch(request::contains);
            if (!invokesExternal) {
                skills.removeIf(skill -> "external-api".equals(skill.getName()));
            }
        }
        // 固定预测模板必须先完成 DataPrep 入口，RTD 返回加工仍作为另一个独立入口保留。
        skills.sort(java.util.Comparator.comparingInt(skill -> "predict-api".equals(skill.getName()) ? 0 : 1));
        // 预测接口有独立的固定模板，不能再叠加通用 RestTemplate/DTO 调用链要求。
        if (skills.stream().anyMatch(skill -> "predict-api".equals(skill.getName()))) {
            skills.removeIf(skill -> "external-api".equals(skill.getName()));
        }
        for (Skill skill : skills) {
            log.info("skill hit name={} path={}", skill.getName(), skill.getSourcePath());
        }
        return skills;
    }

    private boolean matches(String task, Skill skill) {
        if (task == null || skill == null || skill.getKeywords() == null) {
            return false;
        }
        String text = task.toLowerCase(java.util.Locale.ROOT);
        // 排除关键词：任务命中其中任一关键词时，本 skill 不参与匹配
        if (skill.getExcludeKeywords() != null) {
            for (String exclude : skill.getExcludeKeywords()) {
                if (exclude != null && !exclude.isEmpty()
                        && text.contains(exclude.toLowerCase(java.util.Locale.ROOT))) {
                    return false;
                }
            }
        }
        for (String keyword : skill.getKeywords()) {
            if (keyword != null && !keyword.trim().isEmpty()
                    && text.contains(keyword.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        if ("field-slicing".equals(skill.getName())
                && java.util.regex.Pattern.compile("(?:前|后)\\s*\\d+\\s*位").matcher(text).find()) {
            return true;
        }
        return false;
    }

}
