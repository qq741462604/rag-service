package com.cmbchina.llm.skill;


import lombok.Data;

import java.util.ArrayList;
import java.util.List;


@Data
public class Skill {


    /**
     * skill名称
     */
    private String name;

    /**
     * 实际加载的 Skill 文件路径，仅用于命中日志。
     */
    private String sourcePath;


    /**
     * 原始内容
     */
    private String content;



    /**
     * 匹配关键词
     */
    private List<String> keywords =
            new ArrayList<>();



    /**
     * 排除关键词：任务命中其中任一关键词时，本 skill 不参与匹配。
     * 用于 skill 之间互斥，例如通用外调接口 skill 在遇到"模型预测"类任务时排除自身，
     * 从而让位给更具体的 predict-api skill。
     */
    private List<String> excludeKeywords =
            new ArrayList<>();



    /**
     * 强制规则
     */
    private List<String> rules =
            new ArrayList<>();



    /**
     * 禁止行为
     */
    private List<String> forbidden =
            new ArrayList<>();


    private List<String> implementation =
            new ArrayList<>();

    /**
     * 业务验收条件，S1 与 S2 共享，不包含业务代码生成步骤。
     */
    private List<String> acceptance = new ArrayList<>();

    /**
     * 是否每次请求都生效
     */
    private boolean alwaysApply;

    /**
     * Skill指定修改目标
     */
    private SkillTarget target;

    private String template;

}
