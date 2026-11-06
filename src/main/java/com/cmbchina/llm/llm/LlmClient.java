package com.cmbchina.llm.llm;

import java.io.IOException;

/**
 * 大语言模型统一聊天接口，角色适配器负责业务提示词及返回格式。
 */
public interface LlmClient {

    ChatResponse chat(ChatRequest request) throws IOException;
}
