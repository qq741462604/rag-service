package com.cmbchina.llm.planner;


import lombok.Builder;
import lombok.Data;


import java.util.ArrayList;
import java.util.List;



@Data
@Builder
public class CodePlan {



    /**
     * 用户任务
     */
    private String task;



    /**
     * 执行步骤
     */
    @Builder.Default
    private List<PlanStep> steps =
            new ArrayList<>();



}