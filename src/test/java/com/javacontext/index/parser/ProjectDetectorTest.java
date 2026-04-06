package com.javacontext.index.parser;

import com.javacontext.index.config.AppProperties;
import com.javacontext.index.model.ProjectInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProjectDetector单元测试
 */
class ProjectDetectorTest {

    private ProjectDetector projectDetector;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        projectDetector = new ProjectDetector(properties);
    }

    @Test
    void testDetectMavenProject() throws IOException {
        // 创建临时Maven项目
        Path projectPath = tempDir.resolve("test-maven-project");
        Files.createDirectories(projectPath);

        // 创建pom.xml
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-service</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """;
        Files.writeString(projectPath.resolve("pom.xml"), pomContent);

        // 检测项目
        ProjectInfo projectInfo = projectDetector.detectProject(projectPath.toString(), null);

        assertNotNull(projectInfo);
        assertEquals("com.example:test-service:1.0.0-SNAPSHOT", projectInfo.getProjectKey());
        assertEquals("test-service", projectInfo.getProjectName());
        assertEquals(ProjectInfo.ProjectType.MAVEN, projectInfo.getProjectType());
    }

    @Test
    void testGenerateProjectKeyWithManualKey() {
        // 测试手动指定projectKey
        String manualKey = "myorg:my-service:2.0.0";
        String projectKey = projectDetector.generateProjectKey(
                tempDir.toString(), 
                manualKey,
                null, null, null, 
                ProjectInfo.ProjectType.MAVEN);
    
        assertEquals("myorg:my-service:2.0.0", projectKey);
    }

    @Test
    void testNormalizeProjectKey() {
        // 测试projectKey规范化
        String key1 = projectDetector.normalizeProjectKey("COM.Example:My-Service:1.0.0-SNAPSHOT");
        assertEquals("com.example:my-service:1.0.0-snapshot", key1);

        String key2 = projectDetector.normalizeProjectKey("org.test:simple:1.0");
        assertEquals("org.test:simple:1.0", key2);
    }

    @Test
    void testGenerateProjectKeyWithMavenCoords() {
        // 测试使用Maven坐标生成projectKey
        String projectKey = projectDetector.generateProjectKey(
                tempDir.toString(),
                null,
                "com.example",
                "test-service",
                "1.0.0-SNAPSHOT",
                ProjectInfo.ProjectType.MAVEN);

        assertEquals("com.example:test-service:1.0.0-SNAPSHOT", projectKey);
    }

    @Test
    void testDetectNonExistentProject() {
        // 测试检测不存在的项目
        Path nonExistentPath = tempDir.resolve("non-existent");
        ProjectInfo projectInfo = projectDetector.detectProject(nonExistentPath.toString(), null);

        assertNull(projectInfo);
    }
}
