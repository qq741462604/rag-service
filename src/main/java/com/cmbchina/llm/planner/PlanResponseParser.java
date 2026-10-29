package com.cmbchina.llm.planner;

import com.cmbchina.llm.tool.ToolInput;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析模型返回的计划，兼容常见的 JSON 格式偏差。
 *
 * @author summer
 * @date 2026-10-20 00:00:00
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanResponseParser {

    private static final String TOOL_SECTION_BEGIN = "<|tool_calls_section_begin|>";
    private static final String TOOL_SECTION_END = "<|tool_calls_section_end|>";
    private static final String TOOL_CALL_BEGIN = "<|tool_call_begin|>";
    private static final String TOOL_ARGUMENT_BEGIN = "<|tool_call_argument_begin|>";
    private static final String TOOL_CALL_END = "<|tool_call_end|>";
    private static final Pattern SPECIAL_TOOL_HEADER = Pattern.compile(
            "functions\\.(search_code|read_file|list_files|write_file|create_file|patch_file):[0-9]+");

    private final ObjectMapper objectMapper;

    CodePlan parse(String task, String json) {
        try {
            if (json == null
                    ||
                    json.trim().isEmpty()) {

                throw new RuntimeException(
                        "model response is empty"
                );

            }
            // 只修复 JSON 格式，不清理字符串中的 XML/HTML 标签或 Java 泛型，
            // 避免破坏 content、oldText、newText 中的真实文件内容及补丁锚点。
            // 兼容 trailing comma，修复未转义的反斜杠（如 Windows 路径 D:\FlinkTest\demo），
            // 以及将字符串值内部真实存在的控制字符（换行/回车/制表符）转义为 JSON 转义序列，
            // 避免因这些内容导致整个 plan 解析失败。
            String planJson = normalizeSpecialTokenToolCalls(json);
            String cleanedJson = fixInvalidBackslashEscapes(
                    escapeControlCharsInStrings(
                            removeTrailingCommas(extractJson(planJson))
                    )
            );

            // 部分模型在生成 JSON 时可能遗漏某个步骤对象的闭合 '}'，
            // 导致后续 '{' 被解析器误判为嵌套对象，抛出
            // "Unexpected character ('{')": was expecting double-quote to start field name"。
            // 先按标准方式解析；若因对象未闭合失败，再尝试结构修复后重试，对合法 JSON 无副作用。
            JsonNode root;
            try {
                root = readPlanJson(cleanedJson);
            } catch (JsonParseException e) {
                if (isUnclosedObjectError(e)) {
                    String repaired = repairUnclosedObjects(cleanedJson);
                    root = readPlanJson(repaired);
                } else {
                    String repaired = removeRedundantObjectCloser(cleanedJson, e);
                    if (repaired.equals(cleanedJson)) {
                        throw e;
                    }
                    root = readPlanJson(repaired);
                }
            }
            if (root == null || !root.isObject()) {
                throw invalidPlan("根节点必须是 JSON 对象");
            }
            validateModelResponse(root);

            List<PlanStep> steps = new ArrayList<>();
            int index = 1;
            JsonNode stepArray;

            if (root.has("steps")) {
                stepArray = root.get("steps");
            } else if (root.has("plan")) {
                stepArray = root.get("plan");
            } else {
                throw invalidPlan("缺少 steps 数组");
            }
            if (!stepArray.isArray()) {
                throw invalidPlan("steps/plan 必须是数组");
            }

            for (JsonNode node : stepArray) {
                // 任一步骤不可解析时拒绝整份计划，避免静默丢弃修改后执行不完整计划。
                if (!node.isObject()) {
                    throw invalidPlan("第 " + index + " 步必须是对象");
                }
                try {
                    JsonNode inputNode;
                    if (node.has("input")) {
                        inputNode = node.get("input");
                    } else if (node.has("args")) {
                        inputNode = node.get("args");
                    } else {
                        inputNode = objectMapper.createObjectNode();
                    }

                    // 兼容模型把 keyword/path/limit 等直接放在 tool 平级（漏包 input）的情况：
                    // 尝试把节点自身作为输入；若可反序列化成 ToolInput 且至少包含 path 或 keyword，
                    // 则视为合法的 key-param 平铺写法，直接作为该步骤的输入。
                    if (!node.has("input") && !node.has("args")) {
                        try {
                            ToolInput flatInput = objectMapper.treeToValue(node, ToolInput.class);
                            if (flatInput != null
                                    && (flatInput.getPath() != null || flatInput.getKeyword() != null)) {
                                inputNode = node;
                            }
                        } catch (Exception ignore) {
                            // 平铺节点无法转为 ToolInput，回退到空对象输入，保证步骤不因此失败
                        }
                    }

                    if (!node.has("tool") || !node.get("tool").isTextual()
                            || node.get("tool").asText().trim().isEmpty()) {
                        throw invalidPlan("第 " + index + " 步缺少非空字符串 tool");
                    }
                    if (!inputNode.isObject()) {
                        throw invalidPlan("第 " + index + " 步的 input/args 必须是对象");
                    }

                    ToolInput input = objectMapper.treeToValue(inputNode, ToolInput.class);

                    steps.add(
                            PlanStep.builder()
                                    .order(index++)
                                    .tool(node.get("tool").asText())
                                    .input(input)
                                    .description(node.has("description") ? node.get("description").asText() : "")
                                    .build()
                    );
                } catch (JsonProcessingException singleStepError) {
                    throw invalidPlan("第 " + index + " 步的 input/args 字段类型不符合工具参数要求");
                }
            }
            return CodePlan.builder()
                    .task(task)
                    .steps(steps)
                    .build();

        } catch (RuntimeException e) {
            throw e;
        } catch (JsonProcessingException e) {
            // 只反馈位置和纠正要求，不把整段源码或 Jackson 的原文摘录送回模型。
            String location = e.getLocation() == null ? ""
                    : "，第 " + e.getLocation().getLineNr() + " 行，第 "
                    + e.getLocation().getColumnNr() + " 列";
            log.warn("plan json rejected type={} location={}", e.getClass().getSimpleName(), location);
            String reason = e.getOriginalMessage();
            if (reason != null) {
                reason = reason.replaceAll("[\\r\\n]+", " ");
                reason = reason.substring(0, Math.min(160, reason.length()));
            }
            throw invalidPlan("JSON 无法解析" + location + "，原因：" + reason);
        } catch (Exception e) {
            throw new RuntimeException("解析计划失败（" + e.getClass().getSimpleName() + "）", e);
        }
    }

    /**
     * 仅兼容完整的特殊标记工具调用响应；普通 JSON 原样交给原有解析链。
     * 所有调用、参数及结束标记确认完整后才返回计划，不能执行部分识别出的步骤。
     */
    private String normalizeSpecialTokenToolCalls(String response) throws JsonProcessingException {
        String text = response.trim();
        if (!text.startsWith(TOOL_SECTION_BEGIN)) {
            return response;
        }
        ObjectNode plan = objectMapper.createObjectNode();
        ArrayNode steps = plan.putArray("steps");
        int position = TOOL_SECTION_BEGIN.length();
        while (position < text.length()) {
            position = skipToolWhitespace(text, position);
            if (text.startsWith(TOOL_SECTION_END, position)) {
                position = skipToolWhitespace(text, position + TOOL_SECTION_END.length());
                if (position != text.length() || steps.isEmpty()) {
                    throw invalidPlan("特殊标记工具调用为空或结束标记后存在额外内容");
                }
                log.info("plan special-token tool calls normalized steps={}", steps.size());
                return objectMapper.writeValueAsString(plan);
            }
            if (!text.startsWith(TOOL_CALL_BEGIN, position)) {
                throw invalidPlan("特殊标记工具调用缺少完整的调用开始或区段结束标记");
            }
            int headerStart = position + TOOL_CALL_BEGIN.length();
            int argumentsMarker = text.indexOf(TOOL_ARGUMENT_BEGIN, headerStart);
            if (argumentsMarker < 0) {
                throw invalidPlan("特殊标记工具调用缺少参数开始标记");
            }
            Matcher header = SPECIAL_TOOL_HEADER.matcher(text.substring(headerStart, argumentsMarker).trim());
            if (!header.matches()) {
                throw invalidPlan("特殊标记调用头必须为 functions.<现有工具名>:<序号>，不支持未知工具");
            }
            int argumentsStart = skipToolWhitespace(text, argumentsMarker + TOOL_ARGUMENT_BEGIN.length());
            int argumentsEnd = findToolArgumentsEnd(text, argumentsStart);
            String arguments = text.substring(argumentsStart, argumentsEnd);
            JsonNode input = readPlanJson(fixInvalidBackslashEscapes(
                    escapeControlCharsInStrings(removeTrailingCommas(arguments))));
            if (input == null || !input.isObject()) {
                throw invalidPlan("特殊标记工具调用的参数必须是 JSON 对象");
            }
            position = skipToolWhitespace(text, argumentsEnd);
            if (!text.startsWith(TOOL_CALL_END, position)) {
                throw invalidPlan("特殊标记工具调用的参数后缺少调用结束标记");
            }
            position += TOOL_CALL_END.length();
            ObjectNode step = steps.addObject();
            step.put("tool", header.group(1));
            step.set("input", input);
        }
        throw invalidPlan("特殊标记工具调用被截断，缺少区段结束标记");
    }

    private int skipToolWhitespace(String text, int position) {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
        return position;
    }

    /** 按字符串转义状态寻找完整参数对象，不将源码字符串中的花括号或工具标记当作边界。 */
    private int findToolArgumentsEnd(String text, int start) {
        if (start >= text.length() || text.charAt(start) != '{') {
            throw invalidPlan("特殊标记工具调用的参数必须以 JSON 对象开始");
        }
        int depth = 0;
        boolean inString = false;
        for (int position = start; position < text.length(); position++) {
            char character = text.charAt(position);
            if (inString) {
                if (character == '\\') {
                    position++;
                } else if (character == '"') {
                    inString = false;
                }
            } else if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return position + 1;
            }
        }
        throw invalidPlan("特殊标记工具调用的参数 JSON 未完整闭合");
    }

    private JsonNode readPlanJson(String json) throws JsonProcessingException {
        // 不接受合法计划后另附的对象或垃圾字符，防止只解析部分响应。
        return objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
    }

    /** 仅移除 Jackson 已确认出现在数组闭合前的一枚多余对象闭合符，不猜测缺失步骤。 */
    private String removeRedundantObjectCloser(String json, JsonParseException error) {
        String reason = error.getOriginalMessage();
        if (reason == null || !reason.contains("Unexpected close marker '}'")
                || !reason.contains("expected ']'") || error.getLocation() == null) {
            return json;
        }
        long offset = error.getLocation().getCharOffset() - 1;
        if (offset < 0 || offset >= json.length() || json.charAt((int) offset) != '}') {
            return json;
        }
        int position = (int) offset;
        int previous = position - 1;
        while (previous >= 0 && Character.isWhitespace(json.charAt(previous))) {
            previous--;
        }
        int next = position + 1;
        while (next < json.length() && Character.isWhitespace(json.charAt(next))) {
            next++;
        }
        if (previous < 0 || json.charAt(previous) != '}' || next >= json.length() || json.charAt(next) != ']') {
            return json;
        }
        log.warn("plan json removing redundant object closer line={} column={}",
                error.getLocation().getLineNr(), error.getLocation().getColumnNr());
        return json.substring(0, position) + json.substring(position + 1);
    }

    /**
     * 返回可交给现有规划重试流程的有界格式反馈，不能将解析失败伪装成空计划。
     */
    private IllegalArgumentException invalidPlan(String detail) {
        return new IllegalArgumentException("计划 JSON 格式或结构错误：" + detail
                + "。请重新提交完整合法 JSON 对象及 steps 数组；每步包含 tool 字符串和 input 对象。"
                + "content/oldText/newText 必须各为一个正确转义的 JSON 字符串，"
                + "源码中的双引号、反斜杠、换行必须按 JSON 转义，禁止在 JSON 字符串外用 + 拼接。"
                + "不要输出分析文字、Markdown 代码围栏、<tool_call> 或 ToolInput(...) 伪调用；"
                + "读取也必须使用 JSON，例如 {\"steps\":[{\"tool\":\"read_file\",\"input\":{\"path\":\"已确认的真实路径\"}}]}。"
                + "检查对象和数组闭合括号，不要用空 steps 代替尚未完成的操作。");
    }

    private void validateModelResponse(
            JsonNode root
    ) {


        if (!root.has("error")) {

            return;

        }


        JsonNode error =
                root.get("error");


        String message =
                error.has("message")
                        ? error.get("message").asText()
                        : error.toString();


        throw new RuntimeException(
                "model call failed:"
                        +
                        message
        );

    }

    private String extractJson(String text) {
        // 优先提取独立代码围栏，避免说明文字中的花括号被误认为计划起点。
        java.util.regex.Matcher fence = java.util.regex.Pattern
                .compile("(?m)^[\\t ]*```(?:json)?[\\t ]*\\r?\\n")
                .matcher(text);
        if (fence.find()) {
            int start = fence.end();
            java.util.regex.Matcher closing = java.util.regex.Pattern
                    .compile("(?m)^[\\t ]*```[\\t ]*(?:\\r?$)")
                    .matcher(text);
            if (closing.find(start)) {
                return text.substring(start, closing.start()).trim();
            }
        }
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 移除 JSON 中字符串字面量之外的"尾随逗号"（trailing comma），
     * 用于兼容部分新模型在对象/数组最后一个元素后输出多余逗号的情况。
     *
     * <p>实现要点：逐字符扫描并跟踪字符串字面量状态，仅处理结构层（非字符串内）
     * 的逗号，因此不会破坏 oldText/newText 等字段内容中的 Java 代码；对完全合法
     * 的 JSON（如旧模型输出）无副作用，不影响旧模型解析。</p >
     *
     * @param json 原始 JSON 字符串
     * @return 清理尾随逗号后的 JSON 字符串
     */
    private String removeTrailingCommas(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\') {
                    // 转义字符：连同下一个字符一起原样保留，避免把 \" 误判为字符串结束
                    if (i + 1 < json.length()) {
                        sb.append(json.charAt(i + 1));
                        i++;
                    }
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                    sb.append(c);
                } else if (c == ',') {
                    // 向后看（跳过空白），若下一个非空白字符是 } 或 ]，则为尾随逗号，跳过不输出
                    int j = i + 1;
                    while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
                        j++;
                    }
                    if (j < json.length()
                            && (json.charAt(j) == '}' || json.charAt(j) == ']')) {
                        continue;
                    }
                    sb.append(c);
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 将 JSON 字符串字面量内部"真实存在的控制字符"转义为对应的 JSON 转义序列。
     *
     * <p>部分模型在生成 JSON 时，会把多行 Java 源码或带缩进的文本以"真实换行符/回车符/制表符"
     * 直接写入字符串值内部（而非写成 {@code \n}、{@code \r}、{@code \t} 转义序列）。
     * 在 JSON 规范中，字符串值内部不允许出现未转义的控制字符（U+0000 ~ U+001F），
     * 否则标准 JSON 解析会抛出 {@code Invalid control character} 异常，导致整个 plan 解析失败。
     * 该方法逐字符扫描并跟踪字符串字面量状态，仅把字符串值内部真实的控制字符转义为对应的
     * JSON 转义序列（{@code \n}、{@code \r}、{@code \t}、{@code \b}、{@code \f} 或
     * {@code \u005CuXXXX}）；对已合法转义的 JSON 无副作用，不影响正常解析。</p >
     *
     * @param json 原始 JSON 字符串
     * @return 控制字符已转义后的 JSON 字符串
     */
    private String escapeControlCharsInStrings(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    // 已转义字符：连同下一个字符一起原样保留，避免把 \n 等误当作真实控制字符
                    sb.append(c);
                    if (i + 1 < json.length()) {
                        sb.append(json.charAt(i + 1));
                        i++;
                    }
                } else if (c == '"') {
                    inString = false;
                    sb.append(c);
                } else if (c < 0x20) {
                    // 字符串值内部的真实控制字符，转义为对应的 JSON 转义序列
                    switch (c) {
                        case '\n':
                            sb.append("\\n");
                            break;
                        case '\r':
                            sb.append("\\r");
                            break;
                        case '\t':
                            sb.append("\\t");
                            break;
                        case '\b':
                            sb.append("\\b");
                            break;
                        case '\f':
                            sb.append("\\f");
                            break;
                        default:
                            sb.append(String.format("\\u%04x", (int) c));
                            break;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inString = true;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 修复 JSON 字符串字面量内部"未正确转义的反斜杠"。
     *
     * <p>部分模型在生成 JSON 时，会把 Windows 路径等以单反斜杠形式直接写入字符串值内部，
     * 例如 {@code "path": "D:\FlinkTest\demo\..."}。在 JSON 规范中，反斜杠必须转义为
     * {@code \\}，否则 {@code \F}、{@code \d} 等会成为非法转义序列，导致标准 JSON 解析失败。
     * 该方法逐字符扫描并跟踪字符串字面量状态，仅把字符串值内部"非法的反斜杠转义"修正为
     * 合法的 {@code \\} 形式；对已合法转义的 JSON（如旧模型输出）无副作用，不影响旧模型解析。</p >
     *
     * @param json 原始 JSON 字符串
     * @return 修复非法反斜杠转义后的 JSON 字符串
     */
    private String fixInvalidBackslashEscapes(String json) {

        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    if (i + 1 < json.length()) {
                        char next = json.charAt(i + 1);
                        if (isValidJsonEscape(next)) {
                            // 合法转义序列（如 \\、\"、\n、\t 等），原样保留
                            sb.append(c);
                            sb.append(next);
                            i++;
                        } else {
                            // 非法转义：模型漏写了一个反斜杠，这里补成 \\ 后再处理下一字符
                            sb.append('\\').append('\\');
                        }
                    } else {
                        // 字符串结尾处孤立的反斜杠，补成 \\
                        sb.append('\\').append('\\');
                    }
                } else if (c == '"') {
                    inString = false;
                    sb.append(c);
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inString = true;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 判断字符是否为 JSON 中合法的反斜杠转义目标字符。
     *
     * @param c 待判断字符
     * @return 若为合法转义目标字符（" \ / b f n r t u）则返回 true
     */
    private boolean isValidJsonEscape(char c) {
        return c == '"' || c == '\\' || c == '/'
                || c == 'b' || c == 'f'
                || c == 'n' || c == 'r' || c == 't'
                || c == 'u';
    }

    /**
     * 判断 JSON 解析异常是否由"步骤对象未闭合"引起。
     *
     * <p>当模型输出遗漏某个步骤对象的闭合 '}' 时，Jackson 通常抛出
     * "Unexpected character ('{')": was expecting double-quote to start field name，
     * 其特征是错误消息中包含 "was expecting double-quote to start field name"。</p >
     *
     * @param e JSON 解析异常
     * @return 若该异常特征符合"对象未闭合导致的字段名预期错误"则返回 true
     */
    private boolean isUnclosedObjectError(JsonParseException e) {
        String msg = e.getMessage();
        return msg != null
                && msg.contains("was expecting double-quote to start field name");
    }

    /**
     * 修复 JSON 中"步骤对象未闭合"导致的结构错误。
     *
     * <p>部分模型在生成 JSON 时可能遗漏某个对象（如计划 steps 数组中的步骤对象）的闭合 '}'，
     * 导致后续的 '{' 被解析器误判为前一个对象的嵌套值，从而抛出
     * "Unexpected character ('{')": was expecting double-quote to start field name。</p >
     *
     * <p>修复思路：逐字符扫描并维护结构深度栈（记录 '{' 与 '[' 的开启位置）。当在对象内部
     * 遇到 '{'，且其前一个非空白字符是 ','（在对象内逗号后应期待字段名而非 '{'），则判定为
     * 前一个对象未闭合，自动在当前位置前补充 '}'。该修复仅作用于结构层（非字符串字面量内部），
     * 对完全合法的 JSON 无副作用，不影响旧模型解析。</p >
     *
     * @param json 原始 JSON 字符串
     * @return 修复未闭合对象后的 JSON 字符串
     */
    private String repairUnclosedObjects(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length() + 8);
        // 结构深度栈：记录每个未闭合的 '{' 或 '[' 字符
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && !isEscaped(json, i)) {
                inString = !inString;
                sb.append(c);
                continue;
            }
            if (inString) {
                sb.append(c);
                continue;
            }
            if (c == '{') {
                // 若当前栈顶是 '{'（处于对象内），且前一个非空白字符是 ','，
                // 说明前一个对象未闭合（对象内逗号后应期待字段名 " 而非 {），补充 '}'。
                // 持续补充直到栈顶不再是未闭合对象。
                if (!stack.isEmpty() && stack.peek() == '{') {
                    int prev = sb.length() - 1;
                    while (prev >= 0 && Character.isWhitespace(sb.charAt(prev))) {
                        prev--;
                    }
                    if (prev >= 0 && sb.charAt(prev) == ',') {
                        while (!stack.isEmpty() && stack.peek() == '{') {
                            sb.append('}');
                            stack.pop();
                        }
                    }
                }
                stack.push('{');
                sb.append(c);
            } else if (c == '[') {
                stack.push('[');
                sb.append(c);
            } else if (c == '}') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                sb.append(c);
            } else if (c == ']') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                sb.append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 判断指定位置的字符是否为被反斜杠转义（即前导反斜杠数量为奇数）。
     *
     * @param json JSON 字符串
     * @param idx  字符位置
     * @return 若该字符被转义则返回 true
     */
    private boolean isEscaped(String json, int idx) {
        int count = 0;
        for (int i = idx - 1; i >= 0 && json.charAt(i) == '\\'; i--) {
            count++;
        }
        return (count % 2) == 1;
    }
}
