package com.cmbchina.llm.tool;

import com.cmbchina.llm.agent.AgentContext;
import com.cmbchina.llm.planner.MavenDependencySupport;
import com.cmbchina.llm.verify.SurefireReportReader;
import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 执行固定模块、固定测试清单，持续消费输出并核实本轮 Surefire 执行证据。
 *
 * @author IT006901
 * @date 2026-10-13 10:20:00
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunTestTool extends BaseTool {

    private final WorkspaceService workspaceService;
    private final SurefireReportReader reportReader;
    private final MavenDependencySupport dependencySupport;

    @Value("${codex.agent.test-timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${codex.agent.test-java-home:}")
    private String testJavaHome;

    @PostConstruct
    public void validateConfiguration() {
        if (timeoutSeconds < 30 || timeoutSeconds > 1800) {
            throw new IllegalArgumentException("codex.agent.test-timeout-seconds 必须为 30～1800");
        }
    }

    @Override
    public String name() {
        return "run_test";
    }

    @Override
    public String description() {
        return "server-only: run exact module and test classes with fresh Surefire evidence";
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        long started = System.nanoTime();
        Process process = null;
        ExecutorService reader = null;
        OutputBuffer output = new OutputBuffer();
        try {
            ToolInput request = (ToolInput) input;
            String classes = request.getKeyword();
            if (classes == null || classes.length() > 24000
                    || !classes.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*(?:,[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)*")) {
                return failure("必须指定有效完整测试类清单，不允许回退全量测试", output, -1, true);
            }
            Set<String> expected = new LinkedHashSet<>(Arrays.asList(classes.split(",")));
            if (expected.size() > 200) {
                return failure("测试类超过 200 个，请拆分任务", output, -1, true);
            }
            File pom = workspaceService.resolve(request.getPath());
            if (!pom.isFile() || !"pom.xml".equals(pom.getName())) {
                return failure("测试模块 POM 不存在", output, -1, true);
            }
            File module = pom.getParentFile();
            ProcessBuilder builder = new ProcessBuilder(command(classes, output.marker)).directory(module)
                    .redirectErrorStream(true);
            configureTargetJava(builder);
            Map<Path, String> before = reportReader.snapshot(module);
            log.info("run_test start flow={} round={} module={} tests={} timeout={}s",
                    context.getVariables().get("s2_flow_id"), context.getVariables().get("s2_repair_round"),
                    pom, classes, timeoutSeconds);
            process = builder.start();
            Process running = process;
            reader = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "s2-maven-output");
                thread.setDaemon(true);
                return thread;
            });
            Future<?> drain = reader.submit(() -> output.consume(running));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            boolean finished = false;
            boolean launched = false;
            while (System.nanoTime() < deadline) {
                if (!launched && output.supervisorPid != null) {
                    // 先确认本次监督进程 PID，再允许 Maven 启动，避免超时清理时不知道子树归属。
                    process.getOutputStream().write("RUN\n".getBytes(StandardCharsets.UTF_8));
                    process.getOutputStream().flush();
                    launched = true;
                }
                if (output.exitCode != null || process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
            }
            if (!finished) {
                return failure("测试执行超时（" + timeoutSeconds + "秒），停止自动修复，需检查环境或测试阻塞", output, -1, true);
            }
            if (output.exitCode == null) {
                drain.get(5, TimeUnit.SECONDS);
                return failure("Maven 监督进程异常退出，未收到完整执行结果，请检查运行环境", output, -1, true);
            }
            int exitCode = output.exitCode;
            if (!terminateTree(process, output)) {
                return failure("本次 Maven 进程树清理失败，停止自动修复，请检查残留进程", output, exitCode, true);
            }
            drain.get(5, TimeUnit.SECONDS);
            if (output.readError != null) {
                return failure("Maven 输出读取失败: " + output.readError, output, exitCode, true);
            }
            if (exitCode != 0 && output.commandLineError != null) {
                // 参数解析失败时测试尚未开始，不伪装成缺少报告交给模型修改业务代码。
                return failure("Maven/JVM 命令参数不兼容，测试未执行，停止自动修复: "
                        + output.commandLineError, output, exitCode, true);
            }
            if (exitCode != 0 && output.pomValidationFailed) {
                // POM 解析失败发生在测试启动之前，不再为每个测试类追加缺少报告的次生错误。
                String details = String.join("\n", output.errors());
                return failure("Maven POM 校验失败，测试尚未执行；先修复模块 POM 后重新验证。\n"
                        + details.substring(0, Math.min(7000, details.length())), output, exitCode, output.blocked, "POM");
            }
            if (exitCode != 0 && output.dependencyResolutionFailed) {
                // 依赖阶段失败没有测试报告；找不到制品可核对声明，传输/认证故障仍停止。
                boolean blocked = output.blocked || !output.artifactNotFound
                        || !dependencySupport.canRepairDependencyFailure(context, output.errors());
                return failure("Maven 依赖解析失败，测试尚未执行。"
                        + (blocked ? "存在环境故障或缺少本任务可安全撤回的错误声明，停止自动修复；请核对仓库与坐标依据。"
                        : "先核对坐标与版本依据、已有 JAR 类型证据；仅可撤回本任务新增且本次报错的依赖，"
                        + "禁止删除原有依赖、猜版本或修改业务逻辑绕过缺包。")
                        + "\n" + String.join("\n", output.errors()), output, exitCode, blocked, "DEPENDENCY");
            }
            if (exitCode != 0 && output.resourceFailure != null) {
                // 资源阶段未通过时测试还未启动；依赖重复警告不能代替真正的插件异常原因。
                return failure("Maven 资源处理失败，测试尚未执行；停止盲目修改业务代码/POM。"
                        + "请依据日志中的完整异常链检查资源文件或构建环境。\n"
                        + output.resourceFailure + "\n最深层原因: "
                        + (output.rootCause == null ? "输出未包含 Caused by，需检查完整日志" : output.rootCause),
                        output, exitCode, true, "RESOURCE");
            }
            if (exitCode != 0 && output.compilationFailed) {
                // 编译失败时测试尚未开始，只返回根因，不为每个测试类追加缺少报告。
                return failure("Maven 编译失败，测试尚未执行；先修复以下源码或依赖错误。\n"
                        + String.join("\n", output.errors()), output, exitCode, output.blocked, "COMPILE");
            }
            SurefireReportReader.Evidence evidence = reportReader.inspect(module, expected, before);
            // 先保留本次报告中的失败用例与异常根因，避免被重复的控制台输出挤出有界摘要。
            List<String> errors = new ArrayList<>(evidence.errors);
            errors.addAll(output.errors());
            boolean success = exitCode == 0 && evidence.errors.isEmpty();
            if (!success && errors.isEmpty()) {
                errors.add("Maven 测试失败，退出码=" + exitCode);
            }
            String stage = evidence.missingExecutionEvidence ? "TEST_EVIDENCE" : "TEST";
            log.info("run_test finished flow={} round={} module={} stage={} exit={} testsRun={} failedTests={} skipped={} success={} failedClasses={} reports={}",
                    context.getVariables().get("s2_flow_id"), context.getVariables().get("s2_repair_round"),
                    pom, stage, exitCode, evidence.testsRun, evidence.failedTests, evidence.skipped, success,
                    evidence.failedTestClasses, evidence.reports);
            return TestRunResult.builder().success(success).blocked(!success && output.blocked)
                    .stage(stage).failedTests(evidence.failedTests)
                    .failedTestClasses(new ArrayList<>(evidence.failedTestClasses))
                    .exitCode(exitCode).testsRun(evidence.testsRun).skipped(evidence.skipped)
                    .reports(evidence.reports).output(output.tail()).errorMessages(boundedErrors(errors)).build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure("测试执行被中断", output, -1, true);
        } catch (Exception exception) {
            log.warn("run_test blocked: {}", OutputBuffer.redact(exception.getMessage()));
            return failure("测试执行环境或报告异常: " + OutputBuffer.redact(exception.getMessage()), output, -1, true);
        } finally {
            if (process != null) {
                terminateTree(process, output);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                try {
                    process.getOutputStream().close();
                    process.getInputStream().close();
                    process.getErrorStream().close();
                } catch (java.io.IOException exception) {
                    log.debug("Maven output close: {}", exception.getMessage());
                }
            }
            if (reader != null) {
                reader.shutdownNow();
            }
            log.info("run_test end elapsedMs={}", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    private void configureTargetJava(ProcessBuilder builder) throws java.io.IOException {
        Map<String, String> environment = builder.environment();
        boolean configured = testJavaHome != null && !testJavaHome.trim().isEmpty();
        if (configured) {
            File javaHome = new File(testJavaHome.trim()).getCanonicalFile();
            File bin = new File(javaHome, "bin");
            String suffix = isWindows() ? ".exe" : "";
            if (!new File(bin, "java" + suffix).isFile() || !new File(bin, "javac" + suffix).isFile()) {
                throw new IllegalArgumentException("codex.agent.test-java-home 必须指向完整 JDK 根目录，"
                        + "其中 bin/java 和 bin/javac 均须存在: " + javaHome);
            }
            // 只覆盖目标 Maven 子进程环境，生成器自身仍使用原来的 JDK。
            environment.put("JAVA_HOME", javaHome.getPath());
            String pathKey = environment.keySet().stream()
                    .filter(key -> isWindows() ? "PATH".equalsIgnoreCase(key) : "PATH".equals(key))
                    .findFirst().orElse("PATH");
            String inheritedPath = environment.get(pathKey);
            environment.put(pathKey, bin.getPath()
                    + (inheritedPath == null || inheritedPath.isEmpty() ? "" : File.pathSeparator + inheritedPath));
        }
        log.info("run_test Java environment generatorVersion={} generatorHome={} targetJavaHome={} configured={}",
                System.getProperty("java.version"), System.getProperty("java.home"),
                environment.getOrDefault("JAVA_HOME", "<未设置，按 PATH 查找>"), configured);
        // Java 子进程日志必须与监督进程和读取器统一 UTF-8，避免中文编译诊断变成乱码。
        String mavenOptions = environment.getOrDefault("MAVEN_OPTS", "");
        environment.put("MAVEN_OPTS", mavenOptions + " -Dfile.encoding=UTF-8");
    }

    private List<String> command(String classes, String marker) {
        // 不使用旧版 Maven 不支持的 -ntp；下载日志仍由有界输出缓冲处理。
        List<String> arguments = Arrays.asList("-B", "-e", "test", "-Dtest=" + classes,
                "-DfailIfNoTests=true", "-Dsurefire.failIfNoSpecifiedTests=true",
                "-DskipTests=false", "-Dmaven.test.skip=false", "-Dmaven.test.failure.ignore=false",
                "-Dstyle.color=never");
        // JDK 8 不提供 ProcessHandle；监督进程保持存活，直到本次子树完成定向清理。
        if (isWindows()) {
            String parameters = arguments.stream().map(argument -> "'" + argument.replace("'", "''") + "'")
                    .collect(Collectors.joining(" "));
            String script = "$ErrorActionPreference='Stop'; "
                    + "[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding; "
                    + "$OutputEncoding=[Console]::OutputEncoding; "
                    + "[Console]::WriteLine('" + marker + "PID:'+$PID); "
                    + "if ([Console]::ReadLine() -ne 'RUN') { exit 1 }; "
                    + "$LASTEXITCODE=1; & 'mvn.cmd' " + parameters + "; $mavenExit=$LASTEXITCODE; "
                    + "[Console]::WriteLine('" + marker + "EXIT:'+$mavenExit); "
                    + "[Console]::ReadLine() | Out-Null; exit $mavenExit";
            return Arrays.asList("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
        }
        String script = "printf '%s%s\\n' '" + marker + "PID:' \"$$\"; "
                + "IFS= read -r signal; [ \"$signal\" = RUN ] || exit 1; "
                + "mvn \"$@\"; result=$?; printf '%s%s\\n' '" + marker + "EXIT:' \"$result\"; "
                + "IFS= read -r signal; exit \"$result\"";
        List<String> command = new ArrayList<>(Arrays.asList("setsid", "sh", "-c", script, "s2-maven"));
        command.addAll(arguments);
        return command;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean terminateTree(Process process, OutputBuffer output) {
        if (!process.isAlive()) {
            return true;
        }
        if (output.supervisorPid == null) {
            process.destroyForcibly();
            return true;
        }
        boolean interrupted = Thread.interrupted();
        Process cleanup = null;
        try {
            String pid = Long.toString(output.supervisorPid);
            List<String> command = isWindows()
                    ? Arrays.asList("taskkill.exe", "/PID", pid, "/T", "/F")
                    : Arrays.asList("kill", "-KILL", "--", "-" + pid);
            cleanup = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!cleanup.waitFor(5, TimeUnit.SECONDS) || cleanup.exitValue() != 0) {
                log.warn("S2 process tree cleanup failed pid={}", pid);
                return false;
            }
            return process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
            return false;
        } catch (java.io.IOException exception) {
            log.warn("S2 process tree cleanup unavailable: {}", exception.getMessage());
            return false;
        } finally {
            if (cleanup != null) {
                if (cleanup.isAlive()) {
                    cleanup.destroyForcibly();
                }
                try {
                    cleanup.getInputStream().close();
                    cleanup.getOutputStream().close();
                    cleanup.getErrorStream().close();
                } catch (java.io.IOException exception) {
                    log.debug("S2 cleanup stream close: {}", exception.getMessage());
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private TestRunResult failure(String error, OutputBuffer output, int exitCode, boolean blocked) {
        return failure(error, output, exitCode, blocked, "ENVIRONMENT");
    }

    private TestRunResult failure(String error, OutputBuffer output, int exitCode, boolean blocked, String stage) {
        log.warn("run_test stopped stage={} blocked={} reason={}", stage, blocked, error);
        return TestRunResult.builder().success(false).blocked(blocked).exitCode(exitCode)
                .stage(stage)
                .output(output.tail()).reports(Collections.emptyList())
                .errorMessages(Collections.singletonList(error)).build();
    }

    private List<String> boundedErrors(List<String> errors) {
        String summary = OutputBuffer.redact(String.join("\n", errors));
        return Collections.singletonList(summary.substring(0, Math.min(8192, summary.length())));
    }

    private static class OutputBuffer {
        private final String marker = "__S2_" + UUID.randomUUID().toString().replace("-", "") + "__";
        private final StringBuilder tail = new StringBuilder();
        private final StringBuilder failures = new StringBuilder();
        private final StringBuilder compilerDiagnostics = new StringBuilder();
        private volatile boolean blocked;
        private volatile String readError;
        private volatile String commandLineError;
        private String mockitoCapabilityError;
        private volatile boolean pomValidationFailed;
        private volatile boolean dependencyResolutionFailed;
        private volatile boolean artifactNotFound;
        private volatile boolean compilationFailed;
        private boolean collectingCompilerDiagnostics;
        private volatile String resourceFailure;
        private volatile String rootCause;
        private int rootCauseFrames;
        private volatile Long supervisorPid;
        private volatile Integer exitCode;

        private void consume(Process process) {
            try (InputStreamReader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                StringBuilder line = new StringBuilder();
                boolean truncated = false;
                int length;
                while ((length = input.read(buffer)) != -1) {
                    for (int index = 0; index < length; index++) {
                        char character = buffer[index];
                        if (character == '\n' || character == '\r') {
                            if (line.length() > 0 || truncated) {
                                accept(line + (truncated ? " [行已截断]" : ""));
                            }
                            line.setLength(0);
                            truncated = false;
                        } else if (line.length() < 16384) {
                            line.append(character);
                        } else {
                            truncated = true;
                        }
                    }
                }
                if (line.length() > 0) {
                    accept(line.toString());
                }
            } catch (Exception exception) {
                readError = redact(exception.getMessage());
            }
        }

        private synchronized void accept(String raw) {
            if (supervisorPid == null && raw.startsWith(marker + "PID:")) {
                String value = raw.substring((marker + "PID:").length());
                if (value.matches("[1-9][0-9]{0,9}")) {
                    supervisorPid = Long.parseLong(value);
                    return;
                }
            }
            if (supervisorPid != null && raw.startsWith(marker + "EXIT:")) {
                String value = raw.substring((marker + "EXIT:").length());
                if (value.matches("-?[0-9]{1,10}")) {
                    exitCode = Integer.parseInt(value);
                    return;
                }
            }
            String line = redact(raw);
            log.info("[S2 Maven] {}", line);
            append(tail, line, 65536);
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("compilation error") || lower.contains(".java:[")) {
                collectingCompilerDiagnostics = true;
            }
            if (collectingCompilerDiagnostics && lower.contains("[error]")
                    && compilerDiagnostics.length() + line.length() + 1 <= 4096) {
                // 保留最早的缺包/import 根因，滚动错误缓冲在大量后续符号错误中会丢失它。
                compilerDiagnostics.append(line).append('\n');
            }
            if (mockitoCapabilityError == null && lower.contains("mockmaker")
                    && lower.contains("does not support the creation of")
                    && (lower.contains("static mocks") || lower.contains("construction mocks"))) {
                // 此消息没有 [ERROR] 前缀，且可能被后续错误挤出滚动缓冲，单独保留。
                mockitoCapabilityError = line.substring(0, Math.min(1024, line.length()))
                        + "\nMockito 测试能力不匹配：先核对实际 MockMaker、Mockito 版本及 test scope 依赖。"
                        + "Mockito 3/4 的静态/构造器 Mock 需要 inline MockMaker；"
                        + "mockito-inline 应与现有 mockito-core 同版本，Mockito 5 默认 inline。"
                        + "检查是否有 MockMaker 配置强制覆盖，禁止通过删除生产 Bean/静态初始化来修复此错误。";
            }
            if (lower.contains("failed to execute goal") && lower.contains("maven-compiler-plugin:")
                    && (lower.contains(":compile ") || lower.contains(":testcompile "))) {
                compilationFailed = true;
            }
            if (lower.contains("failed to execute goal") && lower.contains("maven-resources-plugin:")) {
                resourceFailure = line.substring(0, Math.min(2048, line.length()));
            }
            if (lower.contains("caused by:")) {
                rootCause = line.substring(0, Math.min(2048, line.length()));
                rootCauseFrames = 0;
            } else if (rootCause != null && lower.trim().startsWith("at ") && rootCauseFrames < 6) {
                // 保留最深异常的关键栈帧，避免只有无消息的异常类型而丢失故障位置。
                String details = rootCause + "\n" + line;
                rootCause = details.substring(0, Math.min(4096, details.length()));
                rootCauseFrames++;
            }
            if (lower.contains("some problems were encountered while processing the poms")
                    || lower.contains("the build could not read")
                    || lower.contains("dependencies.dependency.version") && lower.contains("is missing")) {
                pomValidationFailed = true;
            }
            boolean incompatibleOption = lower.contains("unable to parse command line options")
                    || lower.contains("unrecognized option:") || lower.contains("unrecognised option:")
                    || lower.contains("unknown option:") || lower.contains("missing argument for option:")
                    || lower.contains("unrecognized vm option") || lower.contains("unrecognised vm option");
            if (incompatibleOption) {
                blocked = true;
                if (commandLineError == null) {
                    commandLineError = line.substring(0, Math.min(1024, line.length()));
                }
            }
            if (lower.contains("[error]") || lower.contains("caused by:") || lower.contains("expected:")
                    || lower.contains("assertionerror") || lower.contains("tests run:") || incompatibleOption) {
                append(failures, line, 8192);
            }
            if (lower.contains("could not resolve dependencies") || lower.contains("could not resolve artifact")
                    || lower.contains("could not find artifact") || lower.contains("failure to find ")) {
                dependencyResolutionFailed = true;
            }
            if (lower.contains("could not find artifact") || lower.contains("failure to find ")) {
                artifactNotFound = true;
            }
            if (lower.contains("could not transfer artifact") || lower.contains("non-resolvable parent")
                    || lower.contains("pkix") || lower.contains("unauthorized") || lower.contains("status code: 401")
                    || lower.contains("status code: 403")
                    || lower.contains("unknown host") || lower.contains("unknownhostexception")
                    || lower.contains("connection timed out") || lower.contains("connection refused")
                    || lower.contains("no plugin found for prefix") || lower.contains("not recognized as")
                    || lower.contains("不是内部或外部命令")) {
                blocked = true;
            }
        }

        private void append(StringBuilder buffer, String line, int limit) {
            buffer.append(line).append('\n');
            if (buffer.length() > limit) {
                buffer.delete(0, buffer.length() - limit);
            }
        }

        private synchronized String tail() {
            return tail.toString();
        }

        private synchronized List<String> errors() {
            List<String> errors = new ArrayList<>();
            if (compilationFailed && compilerDiagnostics.length() > 0) {
                errors.add("首批编译诊断（优先按最早错误修复，后续找不到符号可能为次生错误）:\n"
                        + compilerDiagnostics);
            }
            if (mockitoCapabilityError != null) {
                errors.add(mockitoCapabilityError);
            }
            if (failures.length() > 0) {
                errors.add(failures.toString());
            }
            return errors;
        }

        private static String redact(String value) {
            if (value == null) {
                return "";
            }
            return value.replaceAll("\\u001B\\[[;\\d]*m", "")
                    .replaceAll("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@", "$1[REDACTED]@")
                    .replaceAll("(?i)(authorization\\s*[=:]\\s*).*", "$1[REDACTED]")
                    .replaceAll("(?i)((?:password|passwd|token|api[-_]?key|secret|authorization)\\s*[=:]\\s*)[^\\s,;]+", "$1[REDACTED]");
        }
    }

    @Data
    @Builder
    public static class TestRunResult {
        private boolean success;
        private boolean blocked;
        private String stage;
        private int exitCode;
        private int testsRun;
        private int failedTests;
        @Builder.Default
        private List<String> failedTestClasses = Collections.emptyList();
        private int skipped;
        private String output;
        private List<String> reports;
        private List<String> errorMessages;
    }
}
