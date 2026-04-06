package com.javacontext.index.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用配置属性
 * 
 * 从application.yml加载所有配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "java-context-index")
public class AppProperties {

    /**
     * 项目扫描配置
     */
    private ProjectScanConfig project = new ProjectScanConfig();

    /**
     * 全局存储配置 (零Git污染)
     */
    private StorageConfig storage = new StorageConfig();

    /**
     * Embedding模型配置
     */
    private EmbeddingConfig embedding = new EmbeddingConfig();

    /**
     * 向量数据库配置
     */
    private VectorDbConfig vectorDb = new VectorDbConfig();

    /**
     * 搜索配置
     */
    private SearchConfig search = new SearchConfig();

    /**
     * 语义文本构建配置
     */
    private SemanticTextConfig semanticText = new SemanticTextConfig();

    /**
     * 变更检测配置
     */
    private ChangeDetectionConfig changeDetection = new ChangeDetectionConfig();

    /**
     * MCP Server配置
     */
    private McpConfig mcp = new McpConfig();

    @Data
    public static class ProjectScanConfig {
        private List<String> scanPaths = new ArrayList<>();
        private List<String> excludePatterns = new ArrayList<>();
    }

    @Data
    public static class StorageConfig {
        private String baseDir = "~/.java-context-index";
        private String indicesDir = "~/.java-context-index/indices";
        private String cacheDir = "~/.java-context-index/cache";
        private String logDir = "~/.java-context-index/logs";
    }

    @Data
    public static class EmbeddingConfig {
        private String provider = "doubao";
        private int dimension = 4096;
        private int batchSize = 50;
        private int maxRetries = 3;
        private DoubaoConfig doubao = new DoubaoConfig();
        private OpenaiConfig openai = new OpenaiConfig();
        private OllamaConfig ollama = new OllamaConfig();
    }

    @Data
    public static class DoubaoConfig {
        private String apiKey;
        // 新版多模态模型 (推荐)
        // - doubao-embedding: 多模态模型，支持文本和图像
        // - text-embedding-large-3: 4096维，最强语义理解
        // - text-embedding-medium-3: 1024维，平衡性能和成本
        // - text-embedding-small-3: 512维，快速轻量
        private String modelName = "text-embedding-large-3";
        
        // 可选：自定义API端点（默认使用官方端点）
        private String apiEndpoint;
        
        // 可选：请求超时时间（秒），默认60秒
        private int timeout = 60;
        
        // 可选：连接池大小，默认10
        private int connectionPoolSize = 10;
    }

    @Data
    public static class OpenaiConfig {
        private String apiKey;
        private String modelName = "text-embedding-3-small";
    }

    @Data
    public static class OllamaConfig {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "nomic-embed-text";
    }

    @Data
    public static class VectorDbConfig {
        private String type = "milvus";
        private MilvusConfig milvus = new MilvusConfig();
        private ChromadbConfig chromadb = new ChromadbConfig();
        private TypesenseConfig typesense = new TypesenseConfig();
    }

    @Data
    public static class MilvusConfig {
        private String host = "localhost";
        private int port = 19530;
        private String collectionName = "java_code_index";
        private String indexType = "IVF_FLAT";
        private String metricType = "COSINE";
    }

    @Data
    public static class ChromadbConfig {
        private String baseUrl = "http://localhost:8000";
        private String collectionName = "java_code_index";
    }

    @Data
    public static class TypesenseConfig {
        private String host = "localhost";
        private int port = 8108;
        private String apiKey;
        private String collectionName = "java_code_index";
    }

    @Data
    public static class SearchConfig {
        private int defaultTopK = 10;
        private int maxTopK = 50;
        private double semanticWeight = 0.7;
        private double keywordWeight = 0.3;
        private double minScore = 0.5;
    }

    @Data
    public static class SemanticTextConfig {
        private LogicSummaryConfig logicSummary = new LogicSummaryConfig();
    }

    @Data
    public static class LogicSummaryConfig {
        private boolean enabled = true;
        private String model = "qwen-1.5b-chat";
        private String provider = "ollama";
        private OllamaSummaryConfig ollama = new OllamaSummaryConfig();
        private TriggerConditionConfig triggerCondition = new TriggerConditionConfig();
        private GenerationConfig generation = new GenerationConfig();
    }

    @Data
    public static class OllamaSummaryConfig {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "qwen:1.5b-chat";
    }

    @Data
    public static class TriggerConditionConfig {
        private int minCommentLength = 20;
        private boolean skipIfHasJavadoc = true;
    }

    @Data
    public static class GenerationConfig {
        private int maxSummaryLength = 50;
        private int maxCodeLines = 500;
        private long timeout = 5000;
    }

    @Data
    public static class ChangeDetectionConfig {
        private String strategy = "git";
        private boolean enableMethodLevel = false;
        private boolean autoSync = false;
    }

    @Data
    public static class McpConfig {
        private boolean enabled = true;
        private int port = 8080;
        private boolean corsEnabled = true;
    }
}
