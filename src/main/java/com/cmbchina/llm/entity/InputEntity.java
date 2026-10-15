package com.cmbchina.llm.entity;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class InputEntity {

    private List<Map<String, Object>> input;
}
