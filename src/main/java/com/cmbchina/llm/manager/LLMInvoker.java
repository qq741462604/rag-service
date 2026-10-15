package com.cmbchina.llm.manager;

import com.cmbchina.llm.llm.DocGenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LLMInvoker {

    private final DocGenAIClient docGenAIClient;

    public String invoke(String prompt) {
        return docGenAIClient.chat(prompt);
    }
}
