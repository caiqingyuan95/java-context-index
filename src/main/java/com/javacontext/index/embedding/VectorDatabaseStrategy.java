package com.javacontext.index.embedding;

import com.javacontext.index.model.CodeChunk;
import com.javacontext.index.model.SearchResult;

import java.util.List;
import java.util.Map;

/**
 * 向量数据库策略接口
 * 
 * 定义所有向量数据库实现必须遵循的统一接口
 * 支持Milvus、ChromaDB、TypeSense等多引擎切换
 */
public interface VectorDatabaseStrategy {
    
    /**
     * 初始化连接和集合
     */
    void initialize();
    
    /**
     * 关闭连接
     */
    void close();
    
    /**
     * 插入单个代码块
     * 
     * @param chunk 代码块
     * @return 插入的ID
     */
    String insertCodeChunk(CodeChunk chunk);
    
    /**
     * 批量插入代码块
     * 
     * @param chunks 代码块列表
     * @return 插入的ID列表
     */
    List<String> insertCodeChunks(List<CodeChunk> chunks);
    
    /**
     * 向量相似度搜索
     * 
     * @param queryVector 查询向量
     * @param projectKey 项目Key (用于过滤)
     * @param topK 返回结果数量
     * @return 搜索结果列表
     */
    List<SearchResult> search(float[] queryVector, String projectKey, int topK);
    
    /**
     * 混合搜索 (向量+全文)
     * 
     * @param queryVector 查询向量
     * @param queryText 查询文本 (用于全文搜索)
     * @param projectKey 项目Key
     * @param topK 返回结果数量
     * @param semanticWeight 语义权重 (0-1)
     * @param keywordWeight 关键词权重 (0-1)
     * @return 搜索结果列表
     */
    default List<SearchResult> hybridSearch(float[] queryVector, String queryText, 
                                            String projectKey, int topK,
                                            double semanticWeight, double keywordWeight) {
        // 默认实现: 仅使用向量搜索
        return search(queryVector, projectKey, topK);
    }
    
    /**
     * 根据ID删除代码块
     * 
     * @param chunkIds 代码块ID列表
     * @return 删除数量
     */
    int deleteByChunkIds(List<String> chunkIds);
    
    /**
     * 根据文件路径删除代码块
     * 
     * @param projectKey 项目Key
     * @param filePath 文件路径
     * @return 删除数量
     */
    int deleteByFilePath(String projectKey, String filePath);
    
    /**
     * 根据项目Key删除所有代码块
     * 
     * @param projectKey 项目Key
     * @return 删除数量
     */
    int deleteByProjectKey(String projectKey);
    
    /**
     * 更新代码块
     * 
     * @param chunk 代码块
     * @return 是否成功
     */
    boolean updateChunk(CodeChunk chunk);
    
    /**
     * 检查项目是否存在索引
     * 
     * @param projectKey 项目Key
     * @return 是否存在
     */
    boolean existsIndex(String projectKey);
    
    /**
     * 获取统计信息
     * 
     * @param projectKey 项目Key
     * @return 统计信息 (文件数、代码块数等)
     */
    Map<String, Object> getStats(String projectKey);
    
    /**
     * 获取数据库类型名称
     * 
     * @return 类型名称 (milvus/chromadb/typesense)
     */
    String getDbType();
}
