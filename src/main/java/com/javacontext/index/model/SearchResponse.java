package com.javacontext.index.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索结果响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {
    
    /**
     * 搜索结果列表
     */
    private List<SearchResult> results;
    
    /**
     * 查询文本
     */
    private String query;
    
    /**
     * 返回结果数量
     */
    private int topK;
    
    /**
     * 搜索耗时(毫秒)
     */
    private long elapsedMs;
}
