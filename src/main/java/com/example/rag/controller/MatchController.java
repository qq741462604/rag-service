package com.example.rag.controller;

import com.example.rag.model.FieldInfo;
import com.example.rag.service.MatchService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MatchController {

    private final MatchService service;

    public MatchController(MatchService s) {
        this.service = s;
    }

    @GetMapping("/match")
    public Object match(@RequestParam("field") String field) {
        FieldInfo info = service.match(field);
        if (info == null) {
            return "未找到匹配字段：" + field;
        }
        return info;
    }
}
