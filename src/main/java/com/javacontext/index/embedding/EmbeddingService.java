package com.javacontext.index.embedding;

import com.javacontext.index.config.AppProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 服务 - 策略模式,支持多模型热切换
 * 
 * 统一接口:
 * - embed(text): 单个文本向量化
 * - embedAll(texts): 批量向量化 (推荐50个/批)
 * - getDimension(): 获取向量维度
 * 
 * 提供商适配:
 * - 豆包Doubao (通过OpenAI兼容接口)
 * - OpenAI
 * - Ollama (本地)
 */
@Slf4j
@Service
public class EmbeddingService {

    private final AppProperties properties;
    private final EmbeddingModel embeddingModel;
    private final VectorCacheService vectorCacheService;
    private final int dimension;

    public EmbeddingService(AppProperties properties, VectorCacheService vectorCacheService) {
        this.properties = properties;
        this.vectorCacheService = vectorCacheService;
        this.embeddingModel = createEmbeddingModel();
        this.dimension = properties.getEmbedding().getDimension();
        log.info("Embedding服务初始化完成, 提供商: {}, 维度: {}",
                properties.getEmbedding().getProvider(), dimension);
    }

    /**
     * 创建Embedding模型实例
     */
    private EmbeddingModel createEmbeddingModel() {
        String provider = properties.getEmbedding().getProvider().toLowerCase();

        return switch (provider) {
            case "doubao" -> createDoubaoModel();
            case "openai" -> createOpenAiModel();
            case "ollama" -> createOllamaModel();
            default -> throw new IllegalArgumentException("不支持的Embedding提供商: " + provider);
        };
    }

    /**
     * 创建豆包Doubao多模态模型
     * 支持新版多模态Embedding API
     */
    private EmbeddingModel createDoubaoModel() {
        AppProperties.DoubaoConfig config = properties.getEmbedding().getDoubao();
        log.info("初始化豆包多模态Embedding模型: model={}, dimension={}", 
                config.getModelName(), properties.getEmbedding().getDimension());

        // 使用新版豆包多模态模型适配器
        return new DoubaoEmbeddingModel(
                config, 
                properties.getEmbedding().getMaxRetries(),
                properties.getEmbedding().getBatchSize()
        );
    }

    /**
     * 创建OpenAI模型
     */
    private EmbeddingModel createOpenAiModel() {
        AppProperties.OpenaiConfig config = properties.getEmbedding().getOpenai();
        log.info("初始化OpenAI Embedding模型: {}", config.getModelName());

        return OpenAiEmbeddingModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .maxRetries(properties.getEmbedding().getMaxRetries())
                .build();
    }

    /**
     * 创建Ollama本地模型
     */
    private EmbeddingModel createOllamaModel() {
        AppProperties.OllamaConfig config = properties.getEmbedding().getOllama();
        log.info("初始化Ollama本地Embedding模型: {}", config.getModelName());

        return OllamaEmbeddingModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .build();
    }

    /**
     * 单个文本向量化 (带缓存)
     *
     * @param text 输入文本
     * @return 向量 (float数组)
     */
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            log.warn("输入文本为空,返回零向量");
            return new float[dimension];
        }
    
        // 使用缓存
        return vectorCacheService.getVector(text, this::embedWithoutCache);
    }
    
    /**
     * 不带缓存的向量化 (内部使用)
     */
    private float[] embedWithoutCache(String text) {
        try {
            Response<Embedding> response = embeddingModel.embed(text.trim());
            return response.content().vector();
        } catch (Exception e) {
            log.error("Embedding向量化失败: {}", e.getMessage(), e);
            throw new RuntimeException("Embedding向量化失败", e);
        }
    }

    /**
     * 批量文本向量化 (推荐50个/批)
     *
     * @param texts 输入文本列表
     * @return 向量列表
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<float[]> results = new ArrayList<>();
        int batchSize = properties.getEmbedding().getBatchSize();

        // 分批处理
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            try {
                for (String text : batch) {
                    if (text != null && !text.trim().isEmpty()) {
                        Response<Embedding> response = embeddingModel.embed(text.trim());
                        results.add(response.content().vector());
                    } else {
                        results.add(new float[dimension]);
                    }
                }
                log.debug("批量Embedding进度: {}/{}", end, texts.size());
            } catch (Exception e) {
                log.error("批量Embedding失败, 批次: {}-{}, 错误: {}", i, end, e.getMessage(), e);
                throw new RuntimeException("批量Embedding失败", e);
            }
        }

        return results;
    }

    /**
     * 获取向量维度
     */
    public int getDimension() {
        return dimension;
    }
}
