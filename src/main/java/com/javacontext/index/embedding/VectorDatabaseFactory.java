package com.javacontext.index.embedding;

import com.javacontext.index.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量数据库工厂类
 * 
 * 根据配置动态创建对应的向量数据库适配器
 * 支持运行时切换不同的向量数据库引擎
 */
@Slf4j
@Configuration
public class VectorDatabaseFactory {

    private final AppProperties properties;
    private final EmbeddingService embeddingService;

    public VectorDatabaseFactory(AppProperties properties, EmbeddingService embeddingService) {
        this.properties = properties;
        this.embeddingService = embeddingService;
    }

    /**
     * 创建向量数据库策略实例
     * 
     * @return VectorDatabaseStrategy实现
     */
    @Bean
    public VectorDatabaseStrategy vectorDatabaseStrategy() {
        String dbType = properties.getVectorDb().getType();
        
        log.info("创建向量数据库适配器: {}", dbType);
        
        return switch (dbType.toLowerCase()) {
            case "milvus" -> createMilvusAdapter();
            case "chromadb" -> createChromaDBAdapter();
            default -> throw new IllegalArgumentException("不支持的向量数据库类型: " + dbType + 
                    ", 支持的类型: milvus, chromadb");
        };
    }

    /**
     * 创建Milvus适配器
     */
    private VectorDatabaseStrategy createMilvusAdapter() {
        log.info("初始化Milvus向量数据库适配器");
        MilvusVectorDatabaseAdapter adapter = new MilvusVectorDatabaseAdapter(
                properties, embeddingService);
        adapter.initialize();
        return adapter;
    }

    /**
     * 创建ChromaDB适配器
     */
    private VectorDatabaseStrategy createChromaDBAdapter() {
        log.info("初始化ChromaDB向量数据库适配器");
        ChromaDBVectorDatabaseAdapter adapter = new ChromaDBVectorDatabaseAdapter(
                properties, embeddingService);
        adapter.initialize();
        return adapter;
    }

    /**
     * 根据类型创建向量数据库适配器 (用于动态切换)
     * 
     * @param dbType 数据库类型
     * @return VectorDatabaseStrategy实现
     */
    public VectorDatabaseStrategy createAdapter(String dbType) {
        log.info("动态创建向量数据库适配器: {}", dbType);
        
        return switch (dbType.toLowerCase()) {
            case "milvus" -> createMilvusAdapter();
            case "chromadb" -> createChromaDBAdapter();
            default -> throw new IllegalArgumentException("不支持的向量数据库类型: " + dbType);
        };
    }
}
