package com.javacontext.index.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * MCP工具接口
 * 
 * 所有MCP工具必须实现此接口
 */
public interface McpTool {
    
    /**
     * 工具名称
     */
    String getName();
    
    /**
     * 工具描述
     */
    String getDescription();
    
    /**
     * 工具参数定义
     */
    List<ToolParam> getParameters();
    
    /**
     * 执行工具
     * 
     * @param arguments 参数JSON
     * @return 执行结果文本
     */
    String execute(JsonNode arguments);
    
    /**
     * 工具参数定义
     */
    record ToolParam(
            String name,
            String type,
            String description,
            boolean required
    ) {
        public static ToolParam required(String name, String type, String description) {
            return new ToolParam(name, type, description, true);
        }
        
        public static ToolParam optional(String name, String type, String description) {
            return new ToolParam(name, type, description, false);
        }
    }
}
