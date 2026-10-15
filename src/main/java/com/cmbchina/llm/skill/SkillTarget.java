package com.cmbchina.llm.skill;


import lombok.Data;


@Data
public class SkillTarget {


    /**
     * 目标类
     */
    private String className;


    /**
     * 目标方法
     */
    private String methodName;


}