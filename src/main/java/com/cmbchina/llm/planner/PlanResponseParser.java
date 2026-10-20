package com.cmbchina.llm.planner;

import com.cmbchina.llm.tool.ToolInput;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
            String cleanedJson = fixInvalidBackslashEscapes(
                    escapeControlCharsInStrings(
                            removeTrailingCommas(extractJson(json))
                    )
            );

            // 部分模型在生成 JSON 时可能遗漏某个步骤对象的闭合 '}'，
            // 导致后续 '{' 被解析器误判为嵌套对象，抛出
            // "Unexpected character ('{')": was expecting double-quote to start field name"。
            // 先按标准方式解析；若因对象未闭合失败，再尝试结构修复后重试，对合法 JSON 无副作用。
            JsonNode root;
            try {
                root = objectMapper.readTree(cleanedJson);
            } catch (JsonParseException e) {
                if (isUnclosedObjectError(e)) {
                    String repaired = repairUnclosedObjects(cleanedJson);
                    root = objectMapper.readTree(repaired);
                } else {
                    throw e;
                }
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
                throw new RuntimeException("plan field not found");
            }

            for (JsonNode node : stepArray) {
                // 单步容错：模型偶发会输出结构不规范的步骤（如 tool 缺失、或 keyword/path 未包进 input，
                // 或 input 是字符串等无法反序列化为 ToolInput）。为避免单个坏步骤让整个计划解析失败
                // 并中断任务，这里对该步骤做兜底：可修复则修复，不可修复则跳过并记录日志。
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

                    if (!node.has("tool") || node.get("tool").isNull()
                            || node.get("tool").asText().trim().isEmpty()) {
                        // tool 缺失无法执行，跳过该坏步骤，避免整个计划解析失败中断任务
                        log.warn("plan step missing tool, skip step={}", node.size());
                        continue;
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
                } catch (Exception singleStepError) {
                    // 单步反序列化失败（例如 input 为字符串无法转 ToolInput），跳过该坏步骤，
                    // 保留其余正确步骤继续执行，避免单个模型输出异常导致整个任务 parse plan failed。
                    log.warn("skip invalid plan step, node={}, err={}",
                            node.size(), singleStepError.getMessage());
                }
            }
            return CodePlan.builder()
                    .task(task)
                    .steps(steps)
                    .build();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // 模型偶发会输出无法修复的非法 JSON（例如 patch_file 的 oldText/newText 字段内含
            // 超大真实源码，其反斜杠/引号/花括号转义错乱，字符串级修复器无法可靠兜底，二次 readTree
            // 仍失败）。此时若继续抛异常会导致整个 Agent 任务以 parse plan failed 中断。
            // 稳健做法：记录告警并降级返回空计划，让上层走 isEmptyPlan 兜底（不硬中断任务，
            // 由后续迭代/修复流程继续推进或正常收敛），避免一次模型输出格式问题击穿整个任务。
            if (isRecoverableFormatterError(e)) {
                log.warn("plan json irrecoverable, fallback to empty plan. err={}", e.getMessage());
                return CodePlan.builder()
                        .task(task)
                        .steps(new ArrayList<>())
                        .build();
            }
            throw new RuntimeException("parse plan failed:" + json, e);
        }
    }

    /**
     * 判断是否为"模型输出不可修复的非法 JSON"类错误。
     *
     * <p>这类错误通常源于模型在超大字符串字段（如 patch_file 的 oldText/newText，内含完整源码）
     * 中的反斜杠/引号/花括号转义错乱，导致对象结构被破坏，字符串级的容错修复器既无法恢复、
     * 也不代表模型调用本身失败。此时应降级返回空计划而非中断任务。</p >
     *
     * @param e 解析阶段捕获的异常
     * @return 若属于不可修复的格式/结构类错误则返回 true
     */
    private boolean isRecoverableFormatterError(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof JsonParseException) {
                return true;
            }
            if (cause instanceof com.fasterxml.jackson.databind.JsonMappingException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
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
