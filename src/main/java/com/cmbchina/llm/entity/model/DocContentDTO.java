package com.cmbchina.llm.entity.model;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DocContentDTO {

    /** 接口标题 */
    private String apiTitle;

    /** 接口简介 / 摘要 */
    private String summary;

    /** 字段命名规范说明 */
    private String namingConvention;

    /** 请求字段定义（核心） */
    private List<FieldDTO> requestFields;

    /** 响应字段定义（预留，可为空） */
    private List<FieldDTO> responseFields;

    /** 请求报文示例 */
    private Map<String, Object> requestExample;

    /** 响应报文示例 */
    private Map<String, Object> responseExample;

    /** 事件源创建 API（原样保留） */
    private Object eventPost;

    /** 维度映射创建 API（原样保留） */
    private Object eventMappingsPost;

    /** 建表 SQL */
    private String sql;

    /** 额外说明 */
    private String additionalNotes;

    /* ================= getters / setters ================= */

    public String getApiTitle() {
        return apiTitle;
    }

    public void setApiTitle(String apiTitle) {
        this.apiTitle = apiTitle;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getNamingConvention() {
        return namingConvention;
    }

    public void setNamingConvention(String namingConvention) {
        this.namingConvention = namingConvention;
    }

    public List<FieldDTO> getRequestFields() {
        return requestFields;
    }

    public void setRequestFields(List<FieldDTO> requestFields) {
        this.requestFields = requestFields;
    }

    public List<FieldDTO> getResponseFields() {
        return responseFields;
    }

    public void setResponseFields(List<FieldDTO> responseFields) {
        this.responseFields = responseFields;
    }

    public Map<String, Object> getRequestExample() {
        return requestExample;
    }

    public void setRequestExample(Map<String, Object> requestExample) {
        this.requestExample = requestExample;
    }

    public Map<String, Object> getResponseExample() {
        return responseExample;
    }

    public void setResponseExample(Map<String, Object> responseExample) {
        this.responseExample = responseExample;
    }

    public Object getEventPost() {
        return eventPost;
    }

    public void setEventPost(Object eventPost) {
        this.eventPost = eventPost;
    }

    public Object getEventMappingsPost() {
        return eventMappingsPost;
    }

    public void setEventMappingsPost(Object eventMappingsPost) {
        this.eventMappingsPost = eventMappingsPost;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }


    public String getAdditionalNotes() {
        return additionalNotes;
    }

    public void setAdditionalNotes(String additionalNotes) {
        this.additionalNotes = additionalNotes;
    }

    /* ===================================================== */

    /**
     * 字段定义 DTO（请求 / 响应共用）
     */
    public static class FieldDTO {

        /** 业务字段名（如 ipAddress） */
        private String name;

        /** 中文描述 */
        private String description;

        /** 数据类型（String / Integer / Long ...） */
        private String type;

        /** 长度（如 64） */
        private String length;

        /** 是否必填 */
        private boolean required;

        /** 数据库列名 */
        private String dbColumn;

        /** 别名 */
        private List<String> aliases;

        /** 字段来源（核心字段库 / 自定义） */
        private String source;

        /** LLM 置信度 */
        private Double confidence;

        /** 是否需要人工确认 */
        @JsonProperty("needs_review")
        private boolean needsReview;

        /** 备注 */
        private String remarks;

        /* ------------ getters / setters ------------ */

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getLength() {
            return length;
        }

        public void setLength(String length) {
            this.length = length;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public String getDbColumn() {
            return dbColumn;
        }

        public void setDbColumn(String dbColumn) {
            this.dbColumn = dbColumn;
        }

        public List<String> getAliases() {
            return aliases;
        }

        public void setAliases(List<String> aliases) {
            this.aliases = aliases;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public Double getConfidence() {
            return confidence;
        }

        public void setConfidence(Double confidence) {
            this.confidence = confidence;
        }

        public boolean isNeedsReview() {
            return needsReview;
        }

        public void setNeedsReview(boolean needsReview) {
            this.needsReview = needsReview;
        }

        public String getRemarks() {
            return remarks;
        }

        public void setRemarks(String remarks) {
            this.remarks = remarks;
        }
    }
}
