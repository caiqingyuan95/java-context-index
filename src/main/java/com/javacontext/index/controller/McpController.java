package com.javacontext.index.controller;

import com.javacontext.index.mcp.McpServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MCP协议HTTP端点
 * 
 * 提供标准的MCP JSON-RPC接口
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
public class McpController {

    private final McpServerService mcpServerService;

    public McpController(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
        log.info("MCP Controller初始化完成");
    }

    /**
     * MCP主端点 - 处理所有JSON-RPC请求
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public String handleMcpRequest(@RequestBody String request) {
        log.debug("收到MCP HTTP请求");
        return mcpServerService.handleRequest(request);
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "protocol", "MCP 2024-11-05",
                "toolsRegistered", mcpServerService.getToolCount()
        );
    }
}
