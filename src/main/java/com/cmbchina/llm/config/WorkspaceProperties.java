package com.cmbchina.llm.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Data
@Component
@ConfigurationProperties(prefix = "codex.workspace")
public class WorkspaceProperties {
    /**
     * 用户代码根目录
     */
    private String root;
}
