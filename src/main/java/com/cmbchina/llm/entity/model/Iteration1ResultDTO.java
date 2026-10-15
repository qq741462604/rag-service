package com.cmbchina.llm.entity.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class Iteration1ResultDTO {

    private String createSql;
    private String llmReason;
    private LlmResult llmResult;
    private EventPost eventPost;
    private EventMappingsPost eventMappingsPost;

    @Data
    public static class LlmResult {
        private List<LlmField> llmOut;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmField {
        private String canonicalField;
        private String description;
        private String dataType;
        private String length;
        @JsonProperty("column_name")
        private String column_name;
        private Double confidence;
        private List<String> aliases;
        private String source;
    }

    @Data
    public static class EventPost {
        private List<EventField> eventFields;
    }

    @Data
    public static class EventField {
        private String name;
        private String description;
        private Integer type;
        private String defaultValue;
    }

    @Data
    public static class EventMappingsPost {
        private List<EventFieldMapping> eventFieldMappings;
    }

    @Data
    public static class EventFieldMapping {
        private String column;
        private String eventFieldName;
    }
}
