package com.cmbchina.llm.trace;


import lombok.Data;


import java.util.ArrayList;
import java.util.List;



@Data
public class AgentTrace {


    private String task;



    private long startTime;



    private long endTime;



    private List<TraceStep> steps =
            new ArrayList<>();




    public void add(
            TraceStep step
    ){

        steps.add(step);

    }



}