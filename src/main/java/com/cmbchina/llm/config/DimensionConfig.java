package com.cmbchina.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 维度关系配置
 *
 * @author IT006901
 * @date 2026-08-25 10:00:00
 */
@Data
@Component
@ConfigurationProperties(prefix = "dimension")
public class DimensionConfig {

    /**
     * 维度配置列表
     */
    private List<DimensionItem> config;

    /**
     * 维度项
     */
    @Data
    public static class DimensionItem {
        /**
         * 维度ID
         */
        private String id;

        /**
         * 维度名称（中文含义）
         */
        private String name;
    }
}