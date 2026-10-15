package com.cmbchina.llm.entity.model;

import lombok.Data;

import java.util.List;

/**
 * 需求文档数据结构
 * 由大模型解析MD文档生成，包含平台和插件需要的所有元素
 *
 * @author IT006901
 * @date 2026-08-14 00:20:00
 */
@Data
public class RequirementDoc {

    /**
     * 基本信息区块
     */
    private BasicInfo basicInfo;

    /**
     * 事件源字段信息列表（对应all.xlsx的name和description）
     */
    private List<EventSourceField> eventSourceFields;

    /**
     * 字段信息区块（以维度为一组）
     */
    private List<DimensionInfo> dimensionInfos;

    /**
     * 插件需求区块
     */
    private PluginRequirement pluginRequirement;

    /**
     * 补充说明
     */
    private String remarks;

    /**
     * 事件源字段信息（对应all.xlsx的name和description）
     */
    @Data
    public static class EventSourceField {
        /**
         * 字段名
         */
        private String name;

        /**
         * 字段描述
         */
        private String description;
    }

    /**
     * 基本信息
     */
    @Data
    public static class BasicInfo {
        /**
         * 事件源ID
         */
        private String eventSourceId;

        /**
         * 事件源名称
         */
        private String eventSourceName;

        /**
         * 数据保存时间（如：90天、1年）
         */
        private String dataRetentionTime;
    }

    /**
     * 维度信息（以维度为一组）
     */
    @Data
    public static class DimensionInfo {
        /**
         * 维度名称
         */
        private String dimensionName;

        /**
         * 表名
         */
        private String tableName;

        /**
         * 分区键字段（维度字段）
         */
        private String partitionKey;

        /**
         * 主键字段列表（按优先级从左到右排列）
         */
        private List<String> primaryKeys;

        /**
         * 当前维度下需要保留的字段列表
         */
        private List<String> retainFields;
    }

    /**
     * 插件需求区块
     */
    @Data
    public static class PluginRequirement {
        /**
         * 决策码值关系（选填，不填则使用默认配置）
         */
        private DecisionCodeRelation decisionCodeRelation;

        /**
         * 插件改造点列表
         */
        private List<PluginChangePoint> changePoints;
    }

    /**
     * 决策码值关系
     */
    @Data
    public static class DecisionCodeRelation {
        /**
         * 默认决策码值
         */
        private Integer defaultValue;

        /**
         * 决策码值规则列表
         */
        private List<DecisionRule> rules;
    }

    /**
     * 决策码值规则
     */
    @Data
    public static class DecisionRule {
        /**
         * 决策码值
         */
        private Integer code;

        /**
         * 码值描述
         */
        private String description;

        /**
         * 分数最小值
         */
        private Integer minScore;

        /**
         * 分数最大值
         */
        private Integer maxScore;
    }

    /**
     * 插件改造点
     */
    @Data
    public static class PluginChangePoint {

        /**
         * 改造点名称
         */
        private String name;

        /**
         * 类型（数据加工/外调接口）
         */
        private String type;

        /**
         * 描述
         */
        private String description;

        /**
         * 输入字段
         */
        private List<String> inputFields;

        /**
         * 输出字段
         */
        private List<String> outputFields;

        /**
         * 加工逻辑
         */
        private String logic;

        /**
         * 外调接口信息（type为"外调接口"时填写）
         */
        private ExternalApi externalApi;
    }

    /**
     * 外调接口信息
     */
    @Data
    public static class ExternalApi {
        /**
         * 接口地址
         */
        private String url;

        /**
         * 请求方式（GET/POST等）
         */
        private String method;

        /**
         * 请求参数（JSON格式）
         */
        private String requestParams;

        /**
         * 响应参数（JSON格式）
         */
        private String responseParams;
    }
}