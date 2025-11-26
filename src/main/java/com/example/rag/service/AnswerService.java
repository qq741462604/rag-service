package com.example.rag.service;

import com.example.rag.service.VectorSearchService.Scored;
import com.example.rag.model.FieldInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AnswerService {

    @Autowired
    private AnswerClient answerClient;

    @Value("${answer.enabled:true}") // 如需禁用可设为 false
    private boolean answerEnabled;

    public String generateAnswer(String question, List<Scored<FieldInfo>> retrieved) {
        if (!answerEnabled) return "Answer service disabled";

        StringBuilder context = new StringBuilder();
        for (Scored<FieldInfo> s : retrieved) {
            context.append("来源: ").append(s.item.canonicalField).append("\n");
            context.append(s.item.description).append("\n\n");
        }

//        String prompt = "你是企业知识库助手。基于以下知识回答用户问题。\n\n"
//                + context.toString()
//                + "\n用户问题: " + question
//                + "\n若无法从知识库找到相关信息，请明确回复“知识库中未找到相关信息”。";
//        return answerClient.chat(prompt);


        String prompt = "你是企业知识库助手。基于以下知识回答用户问题。\n\n"
                + context.toString()
                + "\n若无法从知识库找到相关信息，请明确回复“知识库中未找到相关信息”。";

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> sys = new HashMap<>();
        sys.put("role", "system");
        sys.put("content", prompt);
        messages.add(sys);
        Map<String, Object> user = new HashMap<>();
        user.put("role", "user");
        user.put("content", question);
        messages.add(user);

        try {
            return answerClient.chat1(messages);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
