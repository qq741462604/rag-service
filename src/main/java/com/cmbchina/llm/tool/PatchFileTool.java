package com.cmbchina.llm.tool;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Component
@Slf4j
@RequiredArgsConstructor
public class PatchFileTool extends BaseTool {

    private final WorkspaceService workspaceService;

    @Override
    public String name() {
        return "patch_file";
    }

    @Override
    public String description() {
        return "replace text in file";
    }

    private void validatePatch(ToolInput input, AgentContext context) {
        if (!wasFileRead(input, context)) {
            throw new RuntimeException("patch_file requires read_file first");
        }
    }

    private boolean wasFileRead(ToolInput input, AgentContext context) {
        /*
         * patch_file 目标路径可能指向 workspace 外的 agent home docs 参考文件
         * （例如 D:\...\LW45.02_rag_service\docs\predict-call-template.md），
         * 此时 workspaceService.resolveExistingFile 会抛出 IllegalArgumentException。
         * 这里做保护，视为"文件未被读取过"，由上层 validatePatch 抛出更友好的提示。
         */
        File patchFile;
        try {
            patchFile = workspaceService.resolveExistingFile(input.getPath());
        } catch (IllegalArgumentException e) {
            return false;
        }

        for (Observation o : context.getObservations()) {
            if (!o.isSuccess()) {
                continue;
            }
            if (!"read_file".equals(o.getTool())) {
                continue;
            }
            if (!(o.getResult() instanceof FileContent)) {
                continue;
            }

            FileContent fc = (FileContent) o.getResult();
            File readFile;
            try {
                readFile = workspaceService.resolve(fc.getPath());
            } catch (Exception e) {
                // read_file 结果路径不在 workspace 内（如 agent home docs 参考模板），
                // 该条 observation 不参与 patch_file 前置校验，跳过
                continue;
            }
            if (sameFile(patchFile, readFile)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object execute(Object input, AgentContext context) throws Exception {
        ToolInput toolInput = (ToolInput) input;
        validatePatch(toolInput, context);
        String path = toolInput.getPath();

        // 使用"按文件名唯一匹配"的纠正解析，避免模型漏写包路径段导致找不到目标文件
        File file = workspaceService.resolveExistingFile(path);
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String result = previewPatch(content, toolInput);
        Files.write(file.toPath(), result.getBytes(StandardCharsets.UTF_8));
        log.info("patch_file success path={} oldLength={} newLength={}",
                workspaceService.relativePath(file), content.length(), result.length());
        return FileOperationResult.builder()
                .action("update")
                .path(workspaceService.relativePath(file))
                .success(true)
                .message("patch success")
                .build();
    }

    /**
     * 在内存中预演局部补丁；规划预检与真实执行必须使用同一套匹配和保护规则。
     * 本方法不读取或写入文件，也不修改模型的补丁参数。
     */
    public String previewPatch(String content, ToolInput toolInput) {
        String lineSeparator = detectLineSeparator(content);
        String oldText = normalizeLineSeparator(toolInput.getOldText(), lineSeparator);
        String newText = normalizeLineSeparator(toolInput.getNewText(), lineSeparator);
        if (newText == null) {
            throw new RuntimeException("newText is null");
        }

        if (content.isEmpty() && (oldText == null || oldText.isEmpty())) {
            return newText;
        }

        String result = applyMatchedPatch(content, oldText, newText);
        if (result != null) {
            return result;
        }

        // 模型可能把未修改的上下文注释重新排版。仅裁掉 old/new 完全相同的整行，
        // 再用剩下的真实唯一片段定位，不把模型改写过的上下文写回文件。
        if (oldText != null && !oldText.trim().isEmpty()) {
            int prefix = commonLinePrefix(oldText, newText);
            int suffix = commonLineSuffix(oldText, newText, prefix);
            int[][] ranges = {{prefix, 0}, {0, suffix}, {prefix, suffix}};
            for (int[] range : ranges) {
                if (range[0] == 0 && range[1] == 0) {
                    continue;
                }
                result = applyMatchedPatch(content,
                        oldText.substring(range[0], oldText.length() - range[1]),
                        newText.substring(range[0], newText.length() - range[1]));
                if (result != null) {
                    log.debug("patch_file context reduced path={} prefixChars={} suffixChars={}",
                            toolInput.getPath(), range[0], range[1]);
                    return result;
                }
            }
        }
        throw new IllegalArgumentException("oldText 未匹配真实内容；请复制原文局部片段，保留原注释和标点");
    }

    private String applyMatchedPatch(String content, String oldText, String newText) {
        if (oldText == null || oldText.trim().isEmpty()) {
            return null;
        }
        String actualOldText = findActualMatch(content, oldText);
        if (actualOldText == null) {
            return null;
        }
        if (actualOldText.trim().equals(content.trim())) {
            throw new RuntimeException("已有非空文件只允许局部修改，禁止通过 patch_file 整文件替换");
        }
        int matchStart = content.indexOf(actualOldText);
        if (content.indexOf(actualOldText, matchStart + 1) >= 0) {
            throw new RuntimeException("oldText 匹配多处，请读取并提供包含上下文的唯一片段");
        }

        return content.substring(0, matchStart) + newText
                + content.substring(matchStart + actualOldText.length());
    }

    private int commonLinePrefix(String oldText, String newText) {
        int end = 0;
        while (end < oldText.length() && end < newText.length()
                && oldText.charAt(end) == newText.charAt(end)) {
            end++;
        }
        return oldText.lastIndexOf('\n', end - 1) + 1;
    }

    private int commonLineSuffix(String oldText, String newText, int prefix) {
        int length = 0;
        while (length < oldText.length() - prefix && length < newText.length() - prefix
                && oldText.charAt(oldText.length() - length - 1)
                == newText.charAt(newText.length() - length - 1)) {
            length++;
        }
        int start = oldText.length() - length;
        if (start > 0 && oldText.charAt(start - 1) != '\n') {
            int newline = oldText.indexOf('\n', start);
            start = newline < 0 ? oldText.length() : newline + 1;
        }
        return oldText.length() - start;
    }

    /**
     * 查找 oldText 在 content 中实际匹配到的片段。
     *
     * <p>规划预检和实际执行共用此方法：
     * 依次尝试严格匹配、空白归一化（token 序列）匹配，
     * 避免因空行数量、空白字符等细微差异导致 patch 失败。</p >
     *
     * @param content       文件真实内容
     * @param oldText       LLM 提供的替换前文本
     * @return content 中实际匹配到的子串；未匹配到返回 null
     */
    private String findActualMatch(String content, String oldText) {
        if (content == null || oldText == null || oldText.isEmpty()) {
            return null;
        }

        // 1. 严格匹配
        if (content.contains(oldText)) {
            return oldText;
        }

        // 空白归一化匹配映射回原始偏移，不能使用归一化字符串的下标截取 CRLF 原文。
        return findFlexibleMatch(content, oldText);
    }

    /**
     * 将文本按空白切分为 token 序列，并记录每个 token 在原文中的起止偏移。
     */
    private static java.util.List<int[]> tokenize(String text) {
        java.util.List<int[]> tokens = new java.util.ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            // 跳过空白
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }

            int start = i;
            // 收集非空白 token
            while (i < n && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            tokens.add(new int[]{start, i});
        }
        return tokens;
    }

    /**
     * 宽松匹配：忽略所有空白差异，基于 token 序列在 content 中定位 oldText。
     *
     * <p>将 content 与 oldText 分别切分为 token 序列（记录每个 token 在原文中的
     * 起止偏移），再在 content 的 token 序列中滑动查找与 oldText token 序列
     * 完全一致的一段，返回其对应的真实子串。</p >
     */
    private String findFlexibleMatch(String content, String oldText) {
        java.util.List<int[]> contentTokens = tokenize(content);
        java.util.List<int[]> oldTokens = tokenize(oldText);
        if (oldTokens.isEmpty()) {
            return null;
        }

        String result = null;
        for (int start = 0;
             start + oldTokens.size() <= contentTokens.size();
             start++) {
            boolean matched = true;
            for (int k = 0; k < oldTokens.size(); k++) {
                String ctk = content.substring(
                        contentTokens.get(start + k)[0],
                        contentTokens.get(start + k)[1]);
                String otk = oldText.substring(
                        oldTokens.get(k)[0],
                        oldTokens.get(k)[1]);
                if (!ctk.equals(otk)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                if (result != null) {
                    throw new IllegalArgumentException("oldText 空白归一化后匹配多处，请提供更完整的唯一上下文");
                }
                int segStart = contentTokens.get(start)[0];
                int segEnd = contentTokens.get(start + oldTokens.size() - 1)[1];
                result = content.substring(segStart, segEnd);
            }
        }
        return result;
    }

    private String detectLineSeparator(String content) {
        if (content.contains("\r\n")) {
            return "\r\n";
        }
        return "\n";
    }

    private String normalizeLineSeparator(String text, String lineSeparator) {
        if (text == null) {
            return null;
        }
        return text.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", lineSeparator);
    }

    private boolean sameFile(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Exception e) {
            return false;
        }
    }
}
