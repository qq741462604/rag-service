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
        log.info("effective skills={}", buildSkillNames(skills));
        return skills;
    }

    private boolean matches(String task, Skill skill) {
        if (task == null || skill == null || skill.getKeywords() == null) {
            return false;
        }
        String text = task.toLowerCase();
        // 排除关键词：任务命中其中任一关键词时，本 skill 不参与匹配
        if (skill.getExcludeKeywords() != null) {
            for (String exclude : skill.getExcludeKeywords()) {
                if (exclude != null && !exclude.isEmpty()
                        && text.contains(exclude.toLowerCase())) {
                    return false;
                }
            }
        }
        for (String keyword : skill.getKeywords()) {
            if (keyword != null && text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String buildSkillNames(List<Skill> skills) {
        List<String> names = new ArrayList<>();
        for (Skill skill : skills) {
            names.add(skill.getName());
        }
        return names.toString();
    }
}
