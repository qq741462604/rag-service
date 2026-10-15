package com.cmbchina.llm.tool;


import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.agent.observation.Observation;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;


@Component
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


    private void validatePatch(
            ToolInput input,
            AgentContext context
    ) {

        if (!wasFileRead(input, context)) {

            throw new RuntimeException(
                    "patch_file requires read_file first"
            );
        }

    }


    private boolean wasFileRead(
            ToolInput input,
            AgentContext context
    ) {

        /*
         * patch_file 目标路径可能指向 workspace 外的 agent home docs 参考文件
         * （例如 D:\...\LW45.02_rag_service\docs\predict-call-template.md），
         * 此时 workspaceService.resolveExistingFile 会抛出 IllegalArgumentException。
         * 这里做保护，视为"文件未被读取过"，由上层 validatePatch 抛出更友好的提示。
         */
        File patchFile;
        try {
            patchFile =
                    workspaceService.resolveExistingFile(
                            input.getPath()
                    );
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


            FileContent fc =
                    (FileContent)o.getResult();


            File readFile;
            try {
                readFile =
                        workspaceService.resolve(
                                fc.getPath()
                        );
            } catch (Exception e) {
                // read_file 结果路径不在 workspace 内（如 agent home docs 参考模板），
                // 该条 observation 不参与 patch_file 前置校验，跳过
                continue;
            }


            if (sameFile(
                    patchFile,
                    readFile
            )) {

                return true;
            }

        }


        return false;
    }


    @Override
    public Object execute(
            Object input,
            AgentContext context
    ) throws Exception {


        ToolInput toolInput =
                (ToolInput)input;


        validatePatch(toolInput, context);

        String path =
                toolInput.getPath();


        // 使用"按文件名唯一匹配"的纠正解析，避免模型漏写包路径段导致找不到目标文件
        File file =
                workspaceService.resolveExistingFile(path);


        String content =
                new String(
                        Files.readAllBytes(
                                file.toPath()
                        ),
                        StandardCharsets.UTF_8
                );


        String lineSeparator =
                detectLineSeparator(content);


        String oldText =
                normalizeLineSeparator(
                        toolInput.getOldText(),
                        lineSeparator
                );


        String newText =
                normalizeLineSeparator(
                        toolInput.getNewText(),
                        lineSeparator
                );

        if (newText == null) {

            throw new RuntimeException(
                    "newText is null"
            );

        }

        if (content.isEmpty()
                &&
                (oldText == null
                        ||
                        oldText.isEmpty())) {

            Files.write(
                    file.toPath(),
                    newText.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            return FileOperationResult.builder()
                    .action("update")
                    .path(
                            workspaceService.relativePath(file)
                    )
                    .success(true)
                    .message("patch success")
                    .build();

        }
        String actualOldText =
                findActualMatch(
                        content,
                        oldText,
                        lineSeparator
                );


        if (actualOldText == null) {


            throw new RuntimeException(
                    "old text not found"
            );

        }


        String result =
                content.replace(
                        actualOldText,
                        newText
                );


        Files.write(
                file.toPath(),
                result.getBytes(
                        StandardCharsets.UTF_8
                )
        );


        return FileOperationResult.builder()
                .action("update")
                .path(
                        workspaceService.relativePath(file)
                )
                .success(true)
                .message("patch success")
                .build();


    }


    /**
     * 查找 oldText 在 content 中实际匹配到的片段。
     *
     * <p>与 {@code QwenPlanner.oldTextExists} 的宽松匹配策略保持一致：
     * 依次尝试严格匹配、行分隔符归一化匹配、空白归一化（token 序列）匹配，
     * 避免因空行数量、空白字符等细微差异导致 patch 失败。</p >
     *
     * @param content       文件真实内容
     * @param oldText       LLM 提供的替换前文本
     * @param lineSeparator 文件使用的行分隔符
     * @return content 中实际匹配到的子串；未匹配到返回 null
     */
    private String findActualMatch(
            String content,
            String oldText,
            String lineSeparator
    ) {

        if (content == null
                || oldText == null
                || oldText.isEmpty()) {

            return null;

        }


        // 1. 严格匹配
        if (content.contains(oldText)) {

            return oldText;

        }


        // 2. 行分隔符归一化匹配（处理 CRLF / LF 差异）
        String normContent =
                content.replace("\r\n", "\n")
                        .replace("\r", "\n");

        String normOldText =
                oldText.replace("\r\n", "\n")
                        .replace("\r", "\n");

        int normIdx =
                normContent.indexOf(normOldText);

        if (normIdx >= 0) {

            return content.substring(
                    normIdx,
                    normIdx + normOldText.length()
            );

        }


        // 3. 空白归一化（token 序列）匹配，映射回真实片段
        return findFlexibleMatch(
                content,
                oldText
        );

    }
    /**
     * 将文本按空白切分为 token 序列，并记录每个 token 在原文中的起止偏移。
     */
    private static java.util.List<int[]> tokenize(
            String text
    ) {

        java.util.List<int[]> tokens =
                new java.util.ArrayList<>();

        int i = 0;

        int n = text.length();

        while (i < n) {

            // 跳过空白
            while (i < n
                    && Character.isWhitespace(
                    text.charAt(i))) {

                i++;

            }

            if (i >= n) {

                break;

            }

            int start = i;

            // 收集非空白 token
            while (i < n
                    && !Character.isWhitespace(
                    text.charAt(i))) {

                i++;

            }

            tokens.add(
                    new int[]{start, i}
            );

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
    private String findFlexibleMatch(
            String content,
            String oldText
    ) {

        java.util.List<int[]> contentTokens =
                tokenize(content);

        java.util.List<int[]> oldTokens =
                tokenize(oldText);

        if (oldTokens.isEmpty()) {

            return null;

        }

        for (int start = 0;
             start + oldTokens.size() <= contentTokens.size();
             start++) {

            boolean matched = true;

            for (int k = 0; k < oldTokens.size(); k++) {

                String ctk =
                        content.substring(
                                contentTokens.get(start + k)[0],
                                contentTokens.get(start + k)[1]
                        );

                String otk =
                        oldText.substring(
                                oldTokens.get(k)[0],
                                oldTokens.get(k)[1]
                        );

                if (!ctk.equals(otk)) {

                    matched = false;

                    break;

                }

            }

            if (matched) {

                int segStart =
                        contentTokens.get(start)[0];

                int segEnd =
                        contentTokens.get(
                                start + oldTokens.size() - 1
                        )[1];

                return content.substring(
                        segStart,
                        segEnd
                );

            }

        }

        return null;

    }


    private String detectLineSeparator(
            String content
    ) {


        if (content.contains("\r\n")) {

            return "\r\n";

        }


        return "\n";

    }


    private String normalizeLineSeparator(
            String text,
            String lineSeparator
    ) {


        if (text == null) {

            return null;

        }


        return text
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", lineSeparator);

    }


    private boolean sameFile(
            File left,
            File right
    ) {


        try {

            return left.getCanonicalFile()
                    .equals(
                            right.getCanonicalFile()
                    );

        } catch (Exception e) {

            return false;

        }

    }


}
