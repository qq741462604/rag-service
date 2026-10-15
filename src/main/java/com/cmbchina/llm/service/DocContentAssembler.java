package com.cmbchina.llm.service;

import com.cmbchina.llm.manager.LLMInvoker;
import com.cmbchina.llm.manager.PromptBuilder;
import com.cmbchina.llm.entity.model.DocContentDTO;
import com.cmbchina.llm.entity.model.Iteration1ResultDTO;
import com.cmbchina.llm.transformer.DocContentParser;
import com.cmbchina.llm.transformer.DocContentTransformer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class DocContentAssembler {
    @Autowired
    private PromptBuilder promptBuilder;
    @Autowired
    private LLMInvoker llmInvoker;
    private ObjectMapper mapper = new ObjectMapper();
    private final DocContentParser parser = new DocContentParser();

    public DocContentDTO generate(Iteration1ResultDTO dto) throws Exception {

        // 1️⃣ 构造 Prompt（关键）
        String prompt = promptBuilder.buildPrompt(dto);
        log.info("Prompt: {}", prompt);

        // 2️⃣ 调用 LLM
        String llmResponse = llmInvoker.invoke(prompt);
        //
        // // 3️⃣ 从 LLM response 中取 content
        String docJson = parser.extractContent(llmResponse);
        //
        // log.info("docJson parser Response: {}", docJson);
        //
        // // 4️⃣ 反序列化成 DocContentDTO
        return mapper.readValue(docJson, DocContentDTO.class);
    }

    public static DocContentDTO assemble(Iteration1ResultDTO input) {

        DocContentDTO doc = new DocContentDTO();
        doc.setApiTitle("接口文档");
        doc.setSql(input.getCreateSql());

        List<DocContentDTO.FieldDTO> requestFields = new ArrayList<>();

        Map<String, Iteration1ResultDTO.LlmField> llmFieldMap = new HashMap<>();
        for (Iteration1ResultDTO.LlmField f : input.getLlmResult().getLlmOut()) {
            llmFieldMap.put(f.getCanonicalField(), f);
        }

        for (Iteration1ResultDTO.EventField eventField :
                input.getEventPost().getEventFields()) {

            Iteration1ResultDTO.LlmField llmField =
                    llmFieldMap.get(eventField.getName());

            DocContentDTO.FieldDTO field = new DocContentDTO.FieldDTO();
            field.setName(eventField.getName());
            field.setDescription(eventField.getDescription());

            if (llmField != null) {
                field.setType(llmField.getDataType());
                field.setLength(llmField.getLength());
                field.setDbColumn(llmField.getColumn_name());
                field.setAliases(llmField.getAliases());
                field.setSource(llmField.getSource());
                field.setConfidence(llmField.getConfidence());
                if (llmField.getConfidence() < 0.95) {
                    field.setNeedsReview(true);
                    field.setRemarks(
                            "置信度=" + llmField.getConfidence()
                                    + "，低于阈值 0.95"
                    );
                }
            } else {
                field.setNeedsReview(true);
                field.setRemarks("未在 LLM 标准字段结果中找到匹配项");
            }

            field.setRequired(true);

            // ⭐统一应用置信度规则
            DocContentTransformer.applyReviewRule(field);

            requestFields.add(field);
        }

        doc.setRequestFields(requestFields);
        doc.setEventPost(input.getEventPost());
        doc.setEventMappingsPost(input.getEventMappingsPost());

        return doc;
    }
}
