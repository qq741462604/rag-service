package com.cmbchina.llm.verify;

import com.cmbchina.llm.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 只接受本次 Maven 刷新的 Surefire XML，逐个确认预期测试类确实执行。
 *
 * @author summer
 * @date 2026-10-20 00:00:00
 */
@Component
@RequiredArgsConstructor
public class SurefireReportReader {

    private static final long MAX_REPORT_BYTES = 1024 * 1024;
    private final WorkspaceService workspaceService;

    public Map<Path, String> snapshot(File module) throws Exception {
        Map<Path, String> result = new LinkedHashMap<>();
        for (Path path : reports(module)) {
            byte[] bytes = bytes(path);
            result.put(path, Files.getLastModifiedTime(path) + ":" + bytes.length + ":"
                    + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        return result;
    }

    public Evidence inspect(File module, Set<String> expected, Map<Path, String> before) throws Exception {
        Evidence result = new Evidence();
        Map<String, Integer> executed = new LinkedHashMap<>();
        expected.forEach(name -> executed.put(name, 0));
        for (Map.Entry<Path, String> report : snapshot(module).entrySet()) {
            if (report.getValue().equals(before.get(report.getKey()))) {
                continue;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(bytes(report.getKey()))).getDocumentElement();
            NodeList cases = root.getElementsByTagName("testcase");
            boolean relevant = false;
            for (int index = 0; index < cases.getLength(); index++) {
                Element test = (Element) cases.item(index);
                String className = test.getAttribute("classname");
                String owner = expected.stream().filter(name -> className.equals(name)
                        || className.startsWith(name + "$" )).findFirst().orElse(null);
                if (owner == null) {
                    continue;
                }
                relevant = true;
                if (test.getElementsByTagName("skipped").getLength() > 0) {
                    result.skipped++;
                    continue;
                }
                result.testsRun++;
                executed.put(owner, executed.get(owner) + 1);
                if (test.getElementsByTagName("failure").getLength() > 0
                        || test.getElementsByTagName("error").getLength() > 0) {
                    result.addError("测试失败: " + className + "#" + test.getAttribute("name"));
                }
            }
            if (relevant) {
                result.reports.add(workspaceService.relativePath(report.getKey().toFile()));
                NodeList suites = root.getElementsByTagName("testsuite");
                checkSuite(root, result);
                for (int index = 0; index < suites.getLength(); index++) {
                    checkSuite((Element) suites.item(index), result);
                }
            }
        }
        executed.forEach((name, count) -> {
            if (count == 0) {
                result.addError("缺少本次有效测试执行证据（未运行、全跳过或仅有旧报告）: " + name);
            }
        });
        return result;
    }

    private void checkSuite(Element suite, Evidence result) {
        if ("testsuite".equals(suite.getTagName())
                && (positive(suite.getAttribute("failures")) || positive(suite.getAttribute("errors")))) {
            result.addError("Surefire 测试套件存在失败: " + suite.getAttribute("name"));
        }
    }

    private boolean positive(String value) {
        return !value.isEmpty() && Integer.parseInt(value) > 0;
    }

    private List<Path> reports(File module) throws Exception {
        File directory = workspaceService.resolve(new File(module, "target/surefire-reports").getPath());
        if (!directory.isDirectory()) {
            return new ArrayList<>();
        }
        try (Stream<Path> paths = Files.list(directory.toPath())) {
            List<Path> reports = paths.filter(path -> path.getFileName().toString().startsWith("TEST-"))
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .limit(201).collect(Collectors.toList());
            if (reports.size() > 200) {
                throw new IllegalArgumentException("Surefire 报告超过 200 份，请缩小模块或人工确认");
            }
            return reports;
        }
    }

    private byte[] bytes(Path path) throws Exception {
        File file = workspaceService.resolve(path.toString());
        if (!file.isFile() || Files.size(file.toPath()) > MAX_REPORT_BYTES) {
            throw new IllegalArgumentException("Surefire 报告不存在或超过 1 MiB: " + file);
        }
        try (java.io.InputStream input = Files.newInputStream(file.toPath());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (output.size() + length > MAX_REPORT_BYTES) {
                    throw new IllegalArgumentException("Surefire 报告读取期间超过 1 MiB: " + file);
                }
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    public static class Evidence {
        public int testsRun;
        public int skipped;
        public final List<String> reports = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        private void addError(String message) {
            if (errors.size() < 32) {
                errors.add(message.substring(0, Math.min(512, message.length())));
            }
        }
    }
}
