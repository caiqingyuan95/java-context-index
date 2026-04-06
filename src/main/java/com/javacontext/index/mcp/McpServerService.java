package com.javacontext.index.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP Server核心服务
 * 
 * Model Context Protocol - 为AI助手提供标准化工具调用接口
 * 支持Tools/Prompts/Resources三大能力
 */
@Slf4j
@Service
public class McpServerService {

    private final ObjectMapper objectMapper;
    private final Map<String, McpTool> tools = new HashMap<>();

    public McpServerService() {
        this.objectMapper = new ObjectMapper();
        log.info("MCP Server服务初始化完成");
    }

    /**
     * 注册MCP工具
     */
    public void registerTool(McpTool tool) {
        tools.put(tool.getName(), tool);
        log.info("注册MCP工具: {}", tool.getName());
    }

    /**
     * 处理MCP请求
     * 
     * @param request JSON-RPC请求
     * @return JSON-RPC响应
     */
    public String handleRequest(String request) {
        try {
            JsonNode requestNode = objectMapper.readTree(request);
            String method = requestNode.path("method").asText();
            JsonNode params = requestNode.path("params");
            String id = requestNode.path("id").asText();

            log.debug("收到MCP请求: method={}, id={}", method, id);

            return switch (method) {
                case "initialize" -> handleInitialize(id);
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolCall(id, params);
                default -> createErrorResponse(id, -32601, "Method not found: " + method);
            };

        } catch (Exception e) {
            log.error("处理MCP请求失败", e);
            return createErrorResponse(null, -32603, "Internal error: " + e.getMessage());
        }
    }

    /**
     * 处理initialize请求
     */
    private String handleInitialize(String id) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "java-context-index");
        serverInfo.put("version", "1.0.0");
        
        return createSuccessResponse(id, result);
    }

    /**
     * 处理tools/list请求
     */
    private String handleToolsList(String id) {
        ObjectNode result = objectMapper.createObjectNode();
        var toolsArray = result.putArray("tools");

        for (McpTool tool : tools.values()) {
            ObjectNode toolNode = toolsArray.addObject();
            toolNode.put("name", tool.getName());
            toolNode.put("description", tool.getDescription());
            
            ObjectNode inputSchema = toolNode.putObject("inputSchema");
            inputSchema.put("type", "object");
            
            // 添加参数定义
            var properties = inputSchema.putObject("properties");
            for (McpTool.ToolParam param : tool.getParameters()) {
                ObjectNode paramNode = properties.putObject(param.name());
                paramNode.put("type", param.type());
                paramNode.put("description", param.description());
                if (param.required()) {
                    inputSchema.putArray("required").add(param.name());
                }
            }
        }

        return createSuccessResponse(id, result);
    }

    /**
     * 处理tools/call请求
     */
    private String handleToolCall(String id, JsonNode params) {
        String toolName = params.path("name").asText();
        JsonNode arguments = params.path("arguments");

        McpTool tool = tools.get(toolName);
        if (tool == null) {
            return createErrorResponse(id, -32602, "Tool not found: " + toolName);
        }

        try {
            // 执行工具
            String result = tool.execute(arguments);
            
            ObjectNode response = objectMapper.createObjectNode();
            var contentArray = response.putArray("content");
            ObjectNode contentItem = contentArray.addObject();
            contentItem.put("type", "text");
            contentItem.put("text", result);
            
            return createSuccessResponse(id, response);

        } catch (Exception e) {
            log.error("执行MCP工具失败: {}", toolName, e);
            return createErrorResponse(id, -32603, "Tool execution failed: " + e.getMessage());
        }
    }

    /**
     * 创建成功响应
     */
    private String createSuccessResponse(String id, JsonNode result) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null && !id.isEmpty()) {
                response.put("id", id);
            }
            response.set("result", result);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("创建成功响应失败", e);
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Response creation failed\"}}";
        }
    }

    /**
     * 创建错误响应
     */
    private String createErrorResponse(String id, int code, String message) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null && !id.isEmpty()) {
                response.put("id", id);
            }
            ObjectNode error = response.putObject("error");
            error.put("code", code);
            error.put("message", message);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("创建错误响应失败", e);
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Error response creation failed\"}}";
        }
    }

    /**
     * 获取已注册的工具数量
     */
    public int getToolCount() {
        return tools.size();
    }
}
