package com.cmbchina.llm.manager;

import com.cmbchina.llm.exception.MyException;
import com.cmbchina.llm.entity.model.Iteration1ResultDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PromptBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 根据阶段一的 DocContentDTO 构建给 LLM 的 Prompt（严格要求 JSON 输出）
     * Java 8 兼容实现（不使用 String.formatted）
     */
    public String buildPrompt(Iteration1ResultDTO dto) {
        try {
            // 将阶段一数据序列化为 JSON 文本
            String parsedDataJson = mapper.writeValueAsString(dto);

            // 使用 StringBuilder 拼接 Prompt（Java 8 兼容）
            StringBuilder sb = new StringBuilder();

            sb.append("SYSTEM:\n");
            sb.append("你是一个企业级文档工程师。你的任务是：根据下方给出的“字段解析结果”（来自阶段一），生成用于自动化生成接口文档的结构化 JSON。");
            sb.append(" **只输出单个 JSON 对象**，不能输出任何额外文字、注释、Markdown、代码块或说明。JSON 必须合法且能被标准 JSON 解析器解析（双引号、无尾随注释）。\n\n");

            sb.append("USER:\n");
            sb.append("下面是阶段一的解析结果（parsedData）。请基于这些数据，返回下列结构的 JSON（字段名称必须完全一致）：\n\n");
            sb.append("{\n");
            sb.append("  \"apiTitle\": \"接口文档标题字符串\",\n");
            sb.append("  \"summary\": \"一句话描述接口用途\",\n");
            sb.append("  \"namingConvention\": \"字段命名规范说明字符串\",\n");
            sb.append("  \"requestFields\": [\n");
            sb.append("    {\n");
            sb.append("      \"name\": \"ipAddress\",\n");
            sb.append("      \"description\": \"字段描述\",\n");
            sb.append("      \"type\": \"String\",\n");
            sb.append("      \"length\": \"64\",\n");
            sb.append("      \"required\": true,\n");
            sb.append("      \"dbColumn\": \"IP_Address\",\n");
            sb.append("      \"aliases\": [\"ip\",\"userIpVal\"],\n");
            sb.append("      \"source\": \"核心字段库\",\n");
            sb.append("      \"confidence\": 0.95,\n");
            sb.append("      \"needs_review\": false,\n");
            sb.append("      \"remarks\": \"备注字符串（可空）\"\n");
            sb.append("    }\n");
            sb.append("  ],\n");
            sb.append("  \"responseFields\": [ /* 推荐或留空 */ ],\n");
            sb.append("  \"requestExample\": { /* dsType + data */ },\n");
            sb.append("  \"responseExample\": { },\n");
            sb.append("  \"eventPost\": { /* 复用 parsedData.eventPost 原样放入 */ },\n");
            sb.append("  \"eventMappingsPost\": { /* 复用 parsedData.eventMappingsPost 原样放入 */ },\n");
            sb.append("  \"sql\": \"建表 SQL 字符串\",\n");
            sb.append("  \"additionalNotes\": \"可选补充说明\"\n");
            sb.append("}\n\n");

            sb.append("规则与要求：\n");
            sb.append("1. 必须只输出上述 JSON（根对象），且字段名严格一致（大小写/下划线需保持）。\n");
            sb.append("2. requestFields 必须从 parsedData.llmResult.llmOut 或 parsedData.eventPost.eventFields 中生成：优先使用 parsedData.llmResult.llmOut；若不存在则使用 parsedData.eventPost.eventFields 作为兜底。\n");
            sb.append("3. 对每个 requestFields，若解析输入包含 confidence 字段并且 < 0.8，则设置 needs_review 为 true，否则 false。若无 confidence 字段，默认 needs_review=false。\n");
            sb.append("4. dbColumn 请使用 parsedData.llmResult.llmOut 中的 column_name（若有）。type 请使用 dataType（若缺失可设为 \"String\"）。length 若缺失可置空字符串。required 默认 true。\n");
            sb.append("5. requestExample.data 应包含所有 requestFields 的示例值（String -> \"示例_<name>\"；int/number -> 0；boolean -> true；object -> {}）。外层包含 \"dsType\": \"xxx\"。\n");
            sb.append("6. responseFields 可给出推荐默认结构（如 code/message/success/data），但也可留空数组 []。\n");
            sb.append("7. eventPost、eventMappingsPost、sql 字段请直接复用或格式化 parsedData 中对应字段（不要新增解释文字）。\n");
            sb.append("8. JSON 中不得包含 null 值（请用 \"\" 或空数组/空对象替代）。\n");
            sb.append("9. 输出 JSON 必须是单个根对象文本，无任何前后缀或额外文字。\n\n");

            sb.append("下面是阶段一的 parsedData（请直接解析此 JSON 并基于它输出文档要素 JSON）：\n");
            sb.append(parsedDataJson);
            sb.append("\n");

            // 返回最终 prompt 字符串
            return sb.toString();

        } catch (Exception e) {
            log.error("构建 prompt 失败", e);
            throw new MyException("构建 prompt 失败");
        }
    }
}
