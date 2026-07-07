package com.example.rag.controller;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Slf4j
public class TestController {

    @GetMapping("/test")
    public String test() {
        return "test";
    }

    @PostMapping("/hello")
    public String hello(@RequestBody JSONObject json) {
        log.info("hello:{}", json);
        return "hello";
    }
}
