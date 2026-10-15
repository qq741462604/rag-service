package com.cmbchina.llm.skill;

import com.cmbchina.llm.config.AgentHomeService;
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
public class SkillLoader {

    private final AgentHomeService agentHomeService;

    public List<Skill> load() {
        List<Skill> result = new ArrayList<>();
        File skillDir = agentHomeService.resolve(".ai/skills");
        if (!skillDir.exists()) {
            return result;
        }
        scan(skillDir, result);
        return result;
    }

    private void scan(File file, List<Skill> result) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    scan(child, result);
                }
            }
            return;
        }
        if (file.getName().endsWith(".md")) {
            result.add(parse(file));
        }
    }

    private Skill parse(File file) {
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append("\n");
            }

            Skill skill = new Skill();
            skill.setName(file.getParentFile().getName());
            skill.setContent(builder.toString());
            parseSections(skill);
            return skill;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void parseSections(Skill skill) {
        String mode = null;
        String pendingKey = null;
        for (String rawLine : skill.getContent().split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            String lower = line.toLowerCase();
            if (lower.startsWith("name:") || lower.startsWith("name=")) {
                String name = extractValue(line);
                if (!name.isEmpty()) {
                    skill.setName(name);
                } else {
                    pendingKey = "name";
                }
                continue;
            }
            if (lower.startsWith("always:") || lower.startsWith("always=")) {
                skill.setAlwaysApply(Boolean.parseBoolean(extractValue(line)));
                continue;
            }
            if (lower.startsWith("keywords")) {
                mode = "keywords";
                continue;
            }
            if (lower.startsWith("excludekeywords")) {
                mode = "excludekeywords";
                continue;
            }
            if (lower.startsWith("rules")) {
                mode = "rules";
                continue;
            }
            if (lower.startsWith("forbidden")) {
                mode = "forbidden";
                continue;
            }
            if (lower.startsWith("implementation")) {
                mode = "implementation";
                continue;
            }
            if (lower.startsWith("target")) {
                mode = "target";
                continue;
            }
            if (line.startsWith("#") || line.startsWith("=")) {
                continue;
            }
            if (pendingKey != null) {
                applyPendingValue(skill, pendingKey, line);
                pendingKey = null;
                continue;
            }

            if ("keywords".equals(mode)) {
                skill.getKeywords().add(cleanLine(line));
                continue;
            }
            if ("excludekeywords".equals(mode)) {
                skill.getExcludeKeywords().add(cleanLine(line));
                continue;
            }
            if ("rules".equals(mode)) {
                skill.getRules().add(line);
                continue;
            }
            if ("forbidden".equals(mode)) {
                skill.getForbidden().add(line);
                continue;
            }
            if ("implementation".equals(mode)) {
                skill.getImplementation().add(rawLine);
                continue;
            }
            if ("target".equals(mode)) {
                pendingKey = parseTarget(skill, line);
            }
        }
    }
    private String parseTarget(Skill skill, String line) {
        if (line.startsWith("class:") || line.startsWith("class=")) {
            String value = extractValue(line);
            setTargetClass(skill, value);
            return value.isEmpty() ? "class" : null;
        }
        if (line.startsWith("method:") || line.startsWith("method=")) {
            String value = extractValue(line);
            setTargetMethod(skill, value);
            return value.isEmpty() ? "method" : null;
        }
        return null;
    }

    private void applyPendingValue(Skill skill, String key, String value) {
        if ("name".equals(key)) {
            skill.setName(value);
        }
        if ("class".equals(key)) {
            setTargetClass(skill, value);
        }
        if ("method".equals(key)) {
            setTargetMethod(skill, value);
        }
    }

    private void setTargetClass(Skill skill, String value) {
        if (value.isEmpty()) {
            return;
        }
        ensureTarget(skill);
        skill.getTarget().setClassName(value);
    }

    private void setTargetMethod(Skill skill, String value) {
        if (!value.isEmpty()) {
            ensureTarget(skill);
            skill.getTarget().setMethodName(value);
        }
    }

    private void ensureTarget(Skill skill) {
        if (skill.getTarget() == null) {
            skill.setTarget(new SkillTarget());
        }
    }

    private String extractValue(String line) {
        int index = line.indexOf(":");
        if (index < 0) {
            index = line.indexOf("=");
        }
        if (index < 0) {
            return "";
        }
        return line.substring(index + 1).trim();
    }

    private String cleanLine(String line) {
        return line.replace("-", "").trim();
    }
}