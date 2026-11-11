package com.cmbchina.llm.llm;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;


@Data
public class LlmRequest {


    private String model;

    /**
     * 修复补丁：仅启用流式时发送 true，未设置时保持原请求结构。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean stream;

    /*
     * 深度思考功能
     */
    @JsonProperty("chat_template_kwargs")
    private Map<String, Object> chatTemplateKwargs;

    /**
     * 模型思考模式，序列化为 thinking.type。
     */
    private Map<String, String> thinking;

    /**
     * 模型推理强度，使用接口要求的下划线参数名。
     */
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;


    private List<Message> messages;



    @Data
    public static class Message{


        private String role;


        private String content;



        public Message(
                String role,
                String content
        ){

            this.role = role;
            this.content = content;

        }

    }


}
