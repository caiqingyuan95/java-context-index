package com.javacontext.index.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 索引元数据模型
 * 
 * 记录索引的状态信息,用于增量更新和索引复用
 * 存储在全局目录: ~/.java-context-index/indices/{projectKey}/index-metadata.json
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexMetadata {
    
    /**
     * 元数据版本号
     */
    private int version;
    
    /**
     * 项目唯一标识
     */
    private String projectKey;
    
    /**
     * 项目名称
     */
    private String projectName;
    
    /**
     * 项目路径
     */
    private String projectPath;
    
    /**
     * 项目类型 (maven, gradle)
     */
    private String projectType;
    
    /**
     * 最后一次索引的Git commit hash
     */
    private String lastIndexedCommit;
    
    /**
     * 索引的基础分支 (固定为main/master)
     */
    private String baseBranch;
    
    /**
     * 索引创建/更新时间
     */
    private LocalDateTime indexedAt;
    
    /**
     * 索引构建者标识
     */
    private String indexedBy;
    
    /**
     * Embedding提供商 (doubao, openai, ollama)
     */
    private String embeddingProvider;
    
    /**
     * 向量数据库类型 (milvus, chromadb, typesense)
     */
    private String vectorDbType;
    
    /**
     * 向量数据库集合名称
     */
    private String vectorCollection;
    
    /**
     * 索引是否完整构建完成
     * - true: 索引已完整构建，可以用于增量更新
     * - false/null: 索引构建中断或不完整，下次需要全量重建
     */
    private Boolean indexComplete;
    
    /**
     * 多模块项目的模块索引状态
     * key: 模块名称 (如 common, api, service)
     * value: 模块索引信息
     */
    @Builder.Default
    private Map<String, ModuleIndexInfo> modules = new HashMap<>();
    
    /**
     * 总索引文件数
     */
    private int totalFiles;
    
    /**
     * 总索引代码块数
     */
    private int totalChunks;
    
    /**
     * 文件级别的索引详情
     * key: 文件相对路径
     * value: 文件索引信息
     */
    @Builder.Default
    private Map<String, FileInfo> files = new HashMap<>();
    
    /**
     * 文件索引信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileInfo {
        
        /**
         * 文件被索引时的commit hash
         */
        private String commitHash;
        
        /**
         * 该文件包含的代码块数量
         */
        private int chunkCount;
        
        /**
         * 代码块在向量数据库中的ID列表
         */
        @Builder.Default
        private java.util.List<String> chunkIds = new java.util.ArrayList<>();
    }
    
    /**
     * 模块索引信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModuleIndexInfo {
        
        /**
         * 模块名称
         */
        private String moduleName;
        
        /**
         * 模块路径
         */
        private String modulePath;
        
        /**
         * 模块是否已完成索引
         */
        private Boolean completed;
        
        /**
         * 模块索引的文件数
         */
        private int fileCount;
        
        /**
         * 模块索引的代码块数
         */
        private int chunkCount;
        
        /**
         * 索引完成时间
         */
        private LocalDateTime completedAt;
    }
}
