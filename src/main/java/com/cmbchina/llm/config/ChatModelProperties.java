package com.cmbchina.llm.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 代码规划、文档生成和需求解析共用的大语言模型配置。
 *
 * @author it0069
 * @date 2026-11-04 17:29:05
 */
@Getter
@Setter
@Component
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "ai.chat")
public class ChatModelProperties {

    private final Environment environment;

    /**
     * 聊天接口地址。
     */
    private String url;

    /**
     * 聊天接口密钥，不参与对象文本输出。
     */
    private String key;

    /**
     * 各业务角色共用的模型名称。
     */
    private String model;

    /**
     * 是否启用深度思考，默认保持关闭。
     */
    private boolean enableThinking;

    /**
     * thinking.type 参数，可选 enabled/disabled，默认 disabled。
     */
    private String thinkingType = "disabled";

    /**
     * reasoning_effort 参数，可选 low/high/max，默认 high。
     */
    private String reasoningEffort = "high";

    /**
     * 连接超时毫秒数，默认保持原来的30分钟。
     */
    private int connectTimeoutMillis = 30 * 60 * 1000;

    /**
     * 读取超时毫秒数，默认保持原来的30分钟。
     */
    private int readTimeoutMillis = 30 * 60 * 1000;

    @PostConstruct
    public void init() {
        // 兼容尚未提供 ai.chat 配置的旧部署，新配置存在时以新配置为准。
        if (url == null) {
            url = environment.getProperty("llm.api.url");
        }
        if (key == null) {
            key = environment.getProperty("llm.api.key", "");
        }
        if (model == null) {
            model = environment.getProperty("llm.model", "qwen-plus");
        }
    }
}
