package com.javacontext.index.embedding;

import com.javacontext.index.config.AppProperties;
import com.javacontext.index.model.CodeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SemanticTextBuilder单元测试
 */
class SemanticTextBuilderTest {

    private SemanticTextBuilder semanticTextBuilder;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        LogicSummaryService logicSummaryService = new LogicSummaryService(properties);
        CodeIntentService codeIntentService = new CodeIntentService();
        semanticTextBuilder = new SemanticTextBuilder(properties, logicSummaryService, codeIntentService);
    }

    @Test
    void testBuildSemanticTextForMethod() {
        // 创建测试代码块
        CodeChunk chunk = new CodeChunk();
        chunk.setId("test-chunk-1");
        chunk.setProjectKey("com.example:test:1.0.0");
        chunk.setProjectName("test");
        chunk.setFilePath("src/main/java/com/example/UserService.java");
        chunk.setChunkType(CodeChunk.ChunkType.METHOD);
        chunk.setName("createUser");
        chunk.setFullyQualifiedName("com.example.UserService.createUser");
        chunk.setPackageName("com.example");
        chunk.setDescription("Creates a new user with validation");
        chunk.setParameters("String username, String email, String password");
        chunk.setReturnType("User");
        chunk.setAnnotations("@Transactional, @Service");
        chunk.setModifier("public");
        chunk.setCode("public User createUser(String username, String email, String password) { ... }");
        chunk.setStartLine(10);
        chunk.setEndLine(50);

        // 构建语义文本
        String semanticText = semanticTextBuilder.buildSemanticText(chunk);

        assertNotNull(semanticText);
        assertTrue(semanticText.contains("Package: com.example"));
        assertTrue(semanticText.contains("Method: createUser"));
        assertTrue(semanticText.contains("Description: Creates a new user with validation"));
        assertTrue(semanticText.contains("Parameters: String username, String email, String password"));
        assertTrue(semanticText.contains("Return type: User"));
        assertTrue(semanticText.contains("Annotations: @Transactional, @Service"));
    }

    @Test
    void testBuildSemanticTextForClass() {
        CodeChunk chunk = new CodeChunk();
        chunk.setId("test-chunk-2");
        chunk.setProjectKey("com.example:test:1.0.0");
        chunk.setProjectName("test");
        chunk.setFilePath("src/main/java/com/example/UserService.java");
        chunk.setChunkType(CodeChunk.ChunkType.CLASS);
        chunk.setName("UserService");
        chunk.setFullyQualifiedName("com.example.UserService");
        chunk.setPackageName("com.example");
        chunk.setDescription("Service class for user management");
        chunk.setAnnotations("@Service");
        chunk.setModifier("public");

        String semanticText = semanticTextBuilder.buildSemanticText(chunk);

        assertNotNull(semanticText);
        assertTrue(semanticText.contains("Class: UserService"));
        assertTrue(semanticText.contains("Description: Service class for user management"));
    }

    @Test
    void testBuildSemanticTextWithNullFields() {
        CodeChunk chunk = new CodeChunk();
        chunk.setId("test-chunk-3");
        chunk.setChunkType(CodeChunk.ChunkType.METHOD);
        chunk.setName("simpleMethod");

        String semanticText = semanticTextBuilder.buildSemanticText(chunk);

        assertNotNull(semanticText);
        assertTrue(semanticText.contains("Method: simpleMethod"));
    }
}
