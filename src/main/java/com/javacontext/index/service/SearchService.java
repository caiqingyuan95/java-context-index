package com.javacontext.index.service;

import com.javacontext.index.config.AppProperties;
import com.javacontext.index.embedding.EmbeddingService;
import com.javacontext.index.embedding.VectorDatabaseStrategy;
import com.javacontext.index.model.SearchResponse;
import com.javacontext.index.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 搜索服务
 * 
 * 职责:
 * 1. 语义搜索代码 (自然语言 -> 向量 -> 相似度搜索)
 * 2. 支持过滤条件 (项目Key、代码块类型、包名等)
 * 3. 返回排序后的搜索结果
 */
@Slf4j
@Service
public class SearchService {

    private final AppProperties properties;
    private final EmbeddingService embeddingService;
    private final VectorDatabaseStrategy vectorDatabase;

    public SearchService(AppProperties properties,
                          EmbeddingService embeddingService,
                          VectorDatabaseStrategy vectorDatabase) {
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.vectorDatabase = vectorDatabase;
    }

    /**
     * 语义搜索代码
     *
     * @param query      查询文本 (自然语言)
     * @param projectKey 项目Key (可选, 用于过滤)
     * @param topK       返回结果数量
     * @return 搜索结果
     */
    public SearchResponse search(String query, String projectKey, int topK) {
        long startTime = System.currentTimeMillis();

        log.info("语义搜索: query={}, projectKey={}, topK={}", query, projectKey, topK);

        // 限制topK
        int maxTopK = properties.getSearch().getMaxTopK();
        if (topK > maxTopK) {
            topK = maxTopK;
        }

        // 1. 查询文本向量化
        float[] queryVector = embeddingService.embed(query);

        // 2. 向量相似度搜索
        List<SearchResult> results = vectorDatabase.search(queryVector, projectKey, topK);

        // 3. 计算相似度分数并排序
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        long elapsed = System.currentTimeMillis() - startTime;

        log.info("搜索完成: {} 个结果, 耗时 {}ms", results.size(), elapsed);

        return SearchResponse.builder()
                .query(query)
                .results(results)
                .topK(topK)
                .elapsedMs(elapsed)
                .build();
    }

    /**
     * 语义搜索代码 (使用默认topK)
     */
    public SearchResponse search(String query, String projectKey) {
        return search(query, projectKey, properties.getSearch().getDefaultTopK());
    }
}
