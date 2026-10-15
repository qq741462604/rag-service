package com.cmbchina.llm.tool;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 写文件工具。
 *
 * <p>write_file 已放开"不得覆盖已存在文件"的限制：分轮读取模板生成代码时，
 * 下一轮常会遇到已存在文件，这是正常场景，允许直接覆盖写入。
 * 局部修改已存在文件仍应使用 patch_file（基于 oldText/newText 精确替换）。</p >
 *
 * <p>写入内容优先级：content 字段 > newText 字段（WriteFileTool 优先读取
 * content 作为完整文件内容，newText 作为从 patch_file 转换时的兜底）。</p >
 *
 * @author IT006901
 * @date 2026-10-10 17:20:00
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteFileTool extends BaseTool {

    private final WorkspaceService workspaceService;

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "create new file";
    }

    @Override
    public Object execute(Object input, AgentContext context) throws Exception {
        ToolInput toolInput = (ToolInput) input;
        File file = workspaceService.resolve(toolInput.getPath());
        boolean existedBefore = file.exists();

        // 放开 write_file 限制：已存在文件也允许覆盖写入。
        // 分轮读取模板生成代码时，下一轮常会遇到已存在文件，这是正常场景，
        // 不应抛错阻断（原有代码要求改用 patch_file，但 LLM 的 oldText 常不匹配，
        // 会导致反复降级 read_file 直至耗尽迭代）。

        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        // 写入内容优先取 content（完整文件内容），其次取 newText（patch 转换兜底）
        String content = toolInput.getContent();
        if (content == null) {
            content = toolInput.getNewText();
        }
        if (content == null) {
            throw new RuntimeException("write_file content is null path=" + toolInput.getPath());
        }

        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return FileOperationResult.builder()
                .action(existedBefore ? "update" : "create")
                .path(workspaceService.relativePath(file))
                .success(true)
                .message("write success")
                .build();
    }
}
