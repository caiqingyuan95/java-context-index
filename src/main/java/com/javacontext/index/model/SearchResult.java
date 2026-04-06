package com.javacontext.index.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索结果模型
 * 
 * 表示一次语义搜索返回的单个结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {
    
    /**
     * 代码块ID
     */
    private String chunkId;
    
    /**
     * 相似度分数 (0-1, 越高越相似)
     */
    private double score;
    
    /**
     * 项目唯一标识
     */
    private String projectKey;
    
    /**
     * 项目名称
     */
    private String projectName;
    
    /**
     * 文件相对路径
     */
    private String filePath;
    
    /**
     * 代码块类型
     */
    private CodeChunk.ChunkType chunkType;
    
    /**
     * 代码块名称
     */
    private String name;
    
    /**
     * 完全限定名
     */
    private String fullyQualifiedName;
    
    /**
     * 代码内容
     */
    private String code;
    
    /**
     * 语义文本
     */
    private String semanticText;
    
    /**
     * Javadoc描述
     */
    private String description;
    
    /**
     * 包名
     */
    private String packageName;
    
    /**
     * 访问修饰符
     */
    private String modifier;
    
    /**
     * 方法参数列表
     */
    private String parameters;
    
    /**
     * 返回值类型
     */
    private String returnType;
    
    /**
     * 注解列表
     */
    private String annotations;
    
    /**
     * 起始行号
     */
    private int startLine;
    
    /**
     * 结束行号
     */
    private int endLine;
}
