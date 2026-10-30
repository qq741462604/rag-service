package com.cmbchina.llm.planner;

import com.cmbchina.llm.agent.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * 只读核对已声明依赖的本地 JAR，不下载、不加载类、不遍历仓库。
 *
 * @author summer
 * @date 2026-10-29 12:00:00
 */
@Component
@Slf4j
public class LocalMavenArtifacts {

    private static final int MAX_JARS = 128;
    private static final int MAX_IMPORTS = 512;
    private static final long MAX_JAR_BYTES = 128L * 1024 * 1024;
    private static final String CACHE = "dependency_local_jar_cache";

    /** 与固定 mvn 命令复用用户设置、PATH 上 Maven 的全局设置及默认仓库。 */
    public File repository() {
        String userHome = System.getProperty("user.home");
        File userSettings = new File(userHome, ".m2/settings.xml");
        String configured = repositorySetting(userSettings);
        if (configured == null) {
            String path = System.getenv("PATH");
            if (path != null) {
                for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    File bin = new File(directory.replace("\"", ""));
                    if (new File(bin, "mvn.cmd").isFile() || new File(bin, "mvn").isFile()) {
                        configured = repositorySetting(new File(bin.getParentFile(), "conf/settings.xml"));
                        break;
                    }
                }
            }
        }
        return configured == null ? new File(userHome, ".m2/repository") : new File(configured);
    }

    private String repositorySetting(File file) {
        if (!file.isFile() || file.length() > 256 * 1024) {
            return null;
        }
        try (java.io.InputStream input = java.nio.file.Files.newInputStream(file.toPath())) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            org.xml.sax.InputSource source = new org.xml.sax.InputSource(input);
            source.setEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
            Element settings = factory.newDocumentBuilder().parse(source).getDocumentElement();
            for (org.w3c.dom.Node node = settings.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element && "localRepository".equals(node.getNodeName())) {
                    String value = node.getTextContent().trim()
                            .replace("${user.home}", System.getProperty("user.home"));
                    if (!value.isEmpty() && !value.contains("${") && new File(value).isAbsolute()) {
                        return value;
                    }
                }
            }
        } catch (Exception exception) {
            log.debug("local Maven settings unavailable path={} reason={}", file, exception.getMessage());
        }
        return null;
    }

    public File artifact(File repository, String coordinate, String version, String extension) {
        String[] parts = coordinate.split(":");
        if (parts.length != 2 || !parts[0].matches("[\\w-]+(?:\\.[\\w-]+)*")
                || !parts[1].matches("[\\w-]+(?:\\.[\\w-]+)*")
                || version == null || !version.matches("[\\w-]+(?:\\.[\\w-]+)*")) {
            return null;
        }
        return new File(repository, parts[0].replace('.', '/') + "/" + parts[1] + "/" + version
                + "/" + parts[1] + "-" + version + "." + extension);
    }

    /** 有界列举指定坐标的本地版本，仅提供事实，不替模型选择或升级版本。 */
    public List<String> versions(File root, String coordinate) {
        List<String> result = new ArrayList<>();
        File example = artifact(root, coordinate, "placeholder", "pom");
        if (example == null) {
            return result;
        }
        File directory = example.getParentFile().getParentFile();
        if (!directory.isDirectory()) {
            return result;
        }
        try (java.nio.file.DirectoryStream<java.nio.file.Path> entries = java.nio.file.Files.newDirectoryStream(directory.toPath())) {
            int count = 0;
            for (java.nio.file.Path entry : entries) {
                if (++count > 64 || result.size() >= 16) {
                    break;
                }
                String version = entry.getFileName().toString();
                File pom = artifact(root, coordinate, version, "pom");
                File jar = artifact(root, coordinate, version, "jar");
                if ((pom != null && pom.isFile()) || (jar != null && jar.isFile())) {
                    result.add(version);
                }
            }
        } catch (IOException exception) {
            log.debug("local versions unavailable coordinate={} reason={}", coordinate, exception.getMessage());
        }
        return result;
    }

    /** 按精确类条目核对；通配包不能证明其中所有类型存在，继续由映射与 Maven 验证。 */
    public Map<String, String> providers(AgentContext context, Map<String, File> jars,
                                         Collection<String> imports) {
        Map<String, String> result = new LinkedHashMap<>();
        Object previous = context.getVariables().get(CACHE);
        JarCache cache = previous instanceof JarCache ? (JarCache) previous : new JarCache();
        context.getVariables().put(CACHE, cache);
        int checked = 0;
        long bytes = 0;
        for (Map.Entry<String, File> candidate : jars.entrySet()) {
            File jar = candidate.getValue();
            if (jar == null || !jar.isFile() || jar.length() > MAX_JAR_BYTES) {
                continue;
            }
            bytes += jar.length();
            if (++checked > MAX_JARS || bytes > 512L * 1024 * 1024) {
                log.debug("local JAR evidence budget reached; remaining imports use dependency mappings");
                break;
            }
            String signature = jar.getAbsolutePath() + ":" + jar.lastModified() + ":" + jar.length();
            Map<String, Boolean> types = cache.entries.get(signature);
            if (types == null) {
                if (cache.entries.size() >= MAX_JARS) {
                    cache.entries.clear();
                }
                types = new LinkedHashMap<>();
                cache.entries.put(signature, types);
            }
            List<String> pending = new ArrayList<>();
            int count = 0;
            for (String imported : imports) {
                if (++count > MAX_IMPORTS) {
                    break;
                }
                Boolean present = types.get(imported);
                if (Boolean.TRUE.equals(present)) {
                    result.putIfAbsent(imported, candidate.getKey());
                } else if (present == null) {
                    pending.add(imported);
                }
            }
            if (pending.isEmpty()) {
                continue;
            }
            try (ZipFile archive = new ZipFile(jar)) {
                for (String imported : pending) {
                    boolean present = containsType(archive, imported);
                    if (types.size() < MAX_IMPORTS) {
                        types.put(imported, present);
                    }
                    if (present) {
                        result.putIfAbsent(imported, candidate.getKey());
                    }
                }
            } catch (IOException exception) {
                log.debug("local JAR evidence unavailable path={} reason={}", jar, exception.getMessage());
            }
        }
        return result;
    }

    private boolean containsType(ZipFile archive, String imported) {
        if (imported.endsWith(".*")) {
            return false;
        }
        String entry = imported.replace('.', '/');
        // 支持显式内部类 import，不将静态成员名或包通配符当成类。
        for (int depth = 0; depth < 8; depth++) {
            if (archive.getEntry(entry + ".class") != null) {
                return true;
            }
            int separator = entry.lastIndexOf('/');
            if (separator < 0) {
                break;
            }
            entry = entry.substring(0, separator) + '$' + entry.substring(separator + 1);
        }
        return false;
    }

    private static class JarCache {
        private final Map<String, Map<String, Boolean>> entries = new LinkedHashMap<>();
    }
}
