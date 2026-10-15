package com.cmbchina.llm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@SpringBootApplication
@EnableAsync
public class RagApplication {
    public static void main(String[] args) throws IOException {
        // 只在非docker环境下重置日志文件
        if (!isDockerProfileActive()) {
            resetLogFile();
        }

        SpringApplication.run(
                RagApplication.class,
                args
        );
    }

    private static void resetLogFile() throws IOException {
        Files.createDirectories(Paths.get("logs"));
        Files.write(Paths.get("logs", "mini-codex.log"), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static boolean isDockerProfileActive() {
        String profiles = System.getProperty("spring.profiles.active", "");
        return profiles.contains("st");
    }
}
