package com.cmbchina.llm.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 应用启动时清理上一轮预检诊断文件，避免新任务排查时混入历史记录。
 *
 * @author summer
 * @date 2026-11-13 00:00:00
 */
@Slf4j
@Component
public class PreflightDiagnosticCleanup {

    private static final String[] DIAGNOSTIC_DIRECTORIES = {
            "logs/s2-preflight",
            "logs/patch-preflight"
    };

    @PostConstruct
    public void clearPreviousDiagnostics() {
        for (String directory : DIAGNOSTIC_DIRECTORIES) {
            clearDirectory(Paths.get(directory).toAbsolutePath().normalize());
        }
    }

    private void clearDirectory(Path directory) {
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            int deleted = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            && Files.deleteIfExists(entry)) {
                        deleted++;
                    }
                }
            }
            if (deleted > 0) {
                log.info("preflight diagnostics cleared directory={} files={}", directory, deleted);
            }
        } catch (IOException | SecurityException exception) {
            // 清理失败不能阻止生成器启动；保留本轮诊断写入和主流程。
            log.warn("preflight diagnostics cleanup failed directory={} type={}",
                    directory, exception.getClass().getSimpleName());
        }
    }
}
