package com.cmbchina.llm.service;

import com.cmbchina.llm.entity.model.DocContentDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class JsonSchemaGenerator {

    public static String generateRequestSchema(DocContentDTO dto)
            throws Exception {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ArrayNode required = mapper.createArrayNode();

        for (DocContentDTO.FieldDTO f : dto.getRequestFields()) {
            ObjectNode field = mapper.createObjectNode();
            field.put("type", mapType(f.getType()));
            field.put("description", f.getDescription());

            props.set(f.getName(), field);

            if (f.isRequired()) {
                required.add(f.getName());
            }
        }

        root.set("properties", props);
        root.set("required", required);

        return mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root);
    }

    private static String mapType(String type) {
        if ("String".equalsIgnoreCase(type)) return "string";
        if ("Integer".equalsIgnoreCase(type)) return "integer";
        return "string";
    }
}
