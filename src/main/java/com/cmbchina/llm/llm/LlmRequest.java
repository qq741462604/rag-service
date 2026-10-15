package com.cmbchina.llm.llm;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;


@Data
public class LlmRequest {


    private String model;

    /*
     * 深度思考功能
     */
    @JsonProperty("chat_template_kwargs")
    private Map<String, Object> chatTemplateKwargs;


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