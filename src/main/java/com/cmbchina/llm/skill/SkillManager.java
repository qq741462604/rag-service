package com.cmbchina.llm.skill;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SkillManager {

    private final SkillMatcher matcher;

    public String buildContext(String task) {
        return buildContext(matcher.match(task));
    }

    public String buildContext(List<Skill> skills) {
        StringBuilder sb = new StringBuilder();
        for (Skill skill : skills) {
            sb.append("\n## Skill:")
                    .append(skill.getName())
                    .append("\n");

            if (skill.getTarget() != null) {
                sb.append("\nTarget:\n");
                sb.append("class=")
                        .append(skill.getTarget().getClassName())
                        .append("\n");
                sb.append("method=")
                        .append(skill.getTarget().getMethodName())
                        .append("\n");
            }

            sb.append("Rules:\n");
            for (String rule : skill.getRules()) {
                sb.append("- ")
                        .append(rule)
                        .append("\n");
            }

            sb.append("\nImplementation:\n");
            String implementation = String.join("\n", skill.getImplementation());
            sb.append(limit(implementation, 8000));

            sb.append("\nAcceptance:\n").append(String.join("\n", skill.getAcceptance()));


            sb.append("\n\nForbidden:\n");
            for (String forbidden : skill.getForbidden()) {
                sb.append("- ")
                        .append(forbidden)
                        .append("\n");
            }
        }
        return sb.toString();
    }

    /** 只传业务契约及验收，不在测试阶段重复注入生产代码生成步骤。 */
    public String buildAcceptanceContext(List<Skill> skills, String sourceClass) {
        StringBuilder result = new StringBuilder();
        for (Skill skill : skills) {
            if (skill.isAlwaysApply() || skill.getTarget() == null || sourceClass != null
                    && !sourceClass.equals(skill.getTarget().getClassName())
                    && !"external-api".equals(skill.getName())) {
                continue;
            }
            result.append("\nSkill 验收: ").append(skill.getName()).append(" target=")
                    .append(skill.getTarget().getClassName()).append("#")
                    .append(skill.getTarget().getMethodName()).append("\nRules:\n")
                    .append(String.join("\n", skill.getRules())).append("\nAcceptance:\n")
                    .append(String.join("\n", skill.getAcceptance())).append("\nForbidden:\n")
                    .append(String.join("\n", skill.getForbidden())).append("\n");
            if (sourceClass != null) {
                result.append("当前测试职责按 ").append(sourceClass)
                        .append(" 的真实实现确定；支持类覆盖自身契约，入口覆盖整体调用和写回，不重复生成业务代码。\n");
            }
        }
        return result.toString();
    }

    private String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() > max) {
            return text.substring(0, max) + "\n...[truncated]";
        }
        return text;
    }
}
