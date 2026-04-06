package com.javacontext.index.parser;

import com.javacontext.index.config.AppProperties;
import com.javacontext.index.model.ProjectInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 项目检测器
 * 
 * 职责:
 * 1. 检测Java项目根目录
 * 2. 提取项目元数据 (Maven/Gradle坐标)
 * 3. 生成项目唯一标识 (projectKey)
 * 4. 计算全局存储路径 (零Git污染)
 * 
 * 项目Key生成策略 (优先级从高到低):
 * 1. 手动指定
 * 2. Maven坐标 (pom.xml)
 * 3. Gradle坐标 (build.gradle)
 * 4. Git远程仓库
 * 5. 项目路径Hash
 */
@Slf4j
@Component
public class ProjectDetector {

    private final AppProperties properties;

    public ProjectDetector(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * 检测Java项目并生成项目信息
     *
     * @param workingDir  工作目录
     * @param manualKey   手动指定的projectKey (可选)
     * @return 项目信息, 如果未检测到项目则返回null
     */
    public ProjectInfo detectProject(String workingDir, String manualKey) {
        Path projectPath = findProjectRoot(Paths.get(workingDir));
        if (projectPath == null) {
            log.warn("未检测到Java项目: {}", workingDir);
            return null;
        }

        log.info("检测到Java项目根目录: {}", projectPath);

        // 提取项目元数据
        ProjectInfo.ProjectType projectType = detectProjectType(projectPath);
        String groupId = null, artifactId = null, version = null;

        if (projectType == ProjectInfo.ProjectType.MAVEN) {
            MavenCoordinate coordinate = extractMavenCoordinate(projectPath);
            if (coordinate != null) {
                groupId = coordinate.groupId;
                artifactId = coordinate.artifactId;
                version = coordinate.version;
            }
        }

        // 生成projectKey
        String projectKey = generateProjectKey(projectPath.toString(), manualKey,
                groupId, artifactId, version, projectType);

        // 获取Git信息
        String gitRemoteUrl = getGitRemoteUrl(projectPath);
        String currentCommitHash = getCurrentCommitHash(projectPath);
        String currentBranch = getCurrentBranch(projectPath);

        // 提取项目名称
        String projectName = artifactId != null ? artifactId : projectPath.getFileName().toString();

        return ProjectInfo.builder()
                .projectKey(projectKey)
                .projectName(projectName)
                .projectPath(projectPath.toString())
                .projectType(projectType)
                .groupId(groupId)
                .artifactId(artifactId)
                .version(version)
                .gitRemoteUrl(gitRemoteUrl)
                .currentCommitHash(currentCommitHash)
                .currentBranch(currentBranch)
                .build();
    }

    /**
     * 查找项目根目录 (向上查找pom.xml或build.gradle)
     */
    private Path findProjectRoot(Path startPath) {
        Path current = startPath.toAbsolutePath().normalize();

        // 最多向上查找10层
        for (int i = 0; i < 10; i++) {
            if (Files.exists(current.resolve("pom.xml")) ||
                    Files.exists(current.resolve("build.gradle")) ||
                    Files.exists(current.resolve("build.gradle.kts"))) {
                return current;
            }

            Path parent = current.getParent();
            if (parent == null) {
                break;
            }
            current = parent;
        }

        return null;
    }

    /**
     * 检测项目类型
     */
    private ProjectInfo.ProjectType detectProjectType(Path projectPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return ProjectInfo.ProjectType.MAVEN;
        }
        if (Files.exists(projectPath.resolve("build.gradle")) ||
                Files.exists(projectPath.resolve("build.gradle.kts"))) {
            return ProjectInfo.ProjectType.GRADLE;
        }
        return ProjectInfo.ProjectType.UNKNOWN;
    }

    /**
     * 从pom.xml提取Maven坐标
     */
    public MavenCoordinate extractMavenCoordinate(Path projectPath) {
        File pomFile = projectPath.resolve("pom.xml").toFile();
        if (!pomFile.exists()) {
            return null;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 禁用外部实体解析,防止XXE攻击
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(pomFile);
            document.getDocumentElement().normalize();

            String groupId = getXmlElementValue(document, "groupId");
            String artifactId = getXmlElementValue(document, "artifactId");
            String version = getXmlElementValue(document, "version");

            // 处理parent POM继承
            if (groupId == null || version == null) {
                NodeList parentNodes = document.getElementsByTagName("parent");
                if (parentNodes.getLength() > 0) {
                    Element parent = (Element) parentNodes.item(0);
                    if (groupId == null) {
                        groupId = getXmlElementText(parent, "groupId");
                    }
                    if (version == null) {
                        version = getXmlElementText(parent, "version");
                    }
                }
            }

            if (artifactId != null) {
                return new MavenCoordinate(
                        groupId != null ? groupId : "unknown",
                        artifactId,
                        version != null ? version : "unknown"
                );
            }
        } catch (ParserConfigurationException | IOException | SAXException e) {
            log.error("解析pom.xml失败: {}", e.getMessage(), e);
        }

        return null;
    }

    /**
     * 获取XML元素值 (支持命名空间)
     */
    private String getXmlElementValue(Document document, String tagName) {
        // 尝试无命名空间
        NodeList nodes = document.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            String value = nodes.item(0).getTextContent().trim();
            if (!value.isEmpty()) {
                return value;
            }
        }

        // 尝试Maven命名空间
        nodes = document.getElementsByTagNameNS("http://maven.apache.org/POM/4.0.0", tagName);
        if (nodes.getLength() > 0) {
            String value = nodes.item(0).getTextContent().trim();
            if (!value.isEmpty()) {
                return value;
            }
        }

        return null;
    }

    /**
     * 获取XML元素文本内容
     */
    private String getXmlElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    /**
     * 生成项目唯一标识 (projectKey)
     * 
     * 优先级: 手动指定 > Maven坐标 > Gradle坐标 > Git远程仓库 > 路径Hash
     */
    public String generateProjectKey(String projectPath, String manualKey,
                                     String groupId, String artifactId, String version,
                                     ProjectInfo.ProjectType projectType) {
        String key = null;

        // 优先级1: 手动指定
        if (manualKey != null && !manualKey.trim().isEmpty()) {
            key = manualKey.trim();
            log.info("使用手动指定的projectKey: {}", key);
        }

        // 优先级2: Maven坐标
        if (key == null && projectType == ProjectInfo.ProjectType.MAVEN
                && groupId != null && artifactId != null) {
            key = groupId + ":" + artifactId + ":" + (version != null ? version : "unknown");
            log.info("使用Maven坐标生成projectKey: {}", key);
        }

        // 优先级4: Git远程仓库
        if (key == null) {
            String gitRemoteUrl = getGitRemoteUrl(Paths.get(projectPath));
            if (gitRemoteUrl != null && !gitRemoteUrl.isEmpty()) {
                key = normalizeGitRemoteUrl(gitRemoteUrl);
                log.info("使用Git远程仓库生成projectKey: {}", key);
            }
        }

        // 优先级5: 项目路径Hash
        if (key == null) {
            key = generatePathHash(projectPath);
            log.info("使用路径Hash生成projectKey: {}", key);
        }

        return normalizeProjectKey(key);
    }

    /**
     * 规范化Git远程仓库URL为projectKey
     */
    private String normalizeGitRemoteUrl(String url) {
        // 移除git@github.com: 或 https://github.com/ 等前缀
        String normalized = url
                .replaceFirst("^git@([^:]+):", "$1/")
                .replaceFirst("^https?://", "")
                .replaceFirst("\\.git$", "")
                .replace(":", "/")
                .replace("/", ":");
        return normalized;
    }

    /**
     * 生成项目路径的Hash作为projectKey
     */
    private String generatePathHash(String projectPath) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(projectPath.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return "local:" + sb.toString().substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return "local:" + Math.abs(projectPath.hashCode());
        }
    }

    /**
     * 规范化projectKey,确保团队一致性
     * 
     * 将projectKey转换为小写,替换非法字符
     */
    public String normalizeProjectKey(String key) {
        if (key == null) {
            return null;
        }
        return key.toLowerCase().trim();
    }

    /**
     * 将projectKey转换为安全的目录名
     * 
     * 替换冒号、空格等非法文件名字符为连字符
     */
    public String normalizeProjectKeyToDir(String projectKey) {
        if (projectKey == null) {
            return "unknown";
        }
        return projectKey.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    /**
     * 获取Git远程仓库URL
     */
    public String getGitRemoteUrl(Path projectPath) {
        try {
            String result = executeGitCommand(projectPath, "git", "remote", "get-url", "origin");
            return result != null ? result.trim() : null;
        } catch (Exception e) {
            log.debug("获取Git远程仓库URL失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取当前Git commit hash
     */
    public String getCurrentCommitHash(Path projectPath) {
        try {
            String result = executeGitCommand(projectPath, "git", "rev-parse", "HEAD");
            return result != null ? result.trim() : null;
        } catch (Exception e) {
            log.debug("获取Git commit hash失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取当前Git分支名称
     */
    public String getCurrentBranch(Path projectPath) {
        try {
            String result = executeGitCommand(projectPath, "git", "rev-parse", "--abbrev-ref", "HEAD");
            return result != null ? result.trim() : null;
        } catch (Exception e) {
            log.debug("获取Git分支失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取索引元数据目录路径
     * 
     * 路径格式: ~/.java-context-index/indices/{projectKey}/
     */
    public Path getMetadataDir(String projectKey) {
        String baseDir = expandTilde(properties.getStorage().getIndicesDir());
        String dirName = normalizeProjectKeyToDir(projectKey);
        return Paths.get(baseDir, dirName);
    }

    /**
     * 获取索引元数据文件路径
     */
    public Path getMetadataFilePath(String projectKey) {
        return getMetadataDir(projectKey).resolve("index-metadata.json");
    }

    /**
     * 展开路径中的 ~ 为用户主目录
     */
    public String expandTilde(String path) {
        if (path.startsWith("~")) {
            String userHome = System.getProperty("user.home");
            return userHome + path.substring(1);
        }
        return path;
    }

    /**
     * 执行Git命令
     */
    private String executeGitCommand(Path workingDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDir.toFile());
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && output.length() > 0) {
                return output.toString().trim();
            }
        }
        return null;
    }

    /**
     * Maven坐标
     */
    public static class MavenCoordinate {
        public final String groupId;
        public final String artifactId;
        public final String version;

        public MavenCoordinate(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }
}
