package com.javacontext.index.mcp;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP工具注册器
 * 
 * 自动注册所有MCP工具到McpServerService
 */
@Slf4j
@Component
public class McpToolRegistrar {

    private final McpServerService mcpServerService;
    private final List<McpTool> mcpTools;

    public McpToolRegistrar(McpServerService mcpServerService, List<McpTool> mcpTools) {
        this.mcpServerService = mcpServerService;
        this.mcpTools = mcpTools;
    }

    @PostConstruct
    public void registerAllTools() {
        log.info("开始注册MCP工具, 共 {} 个", mcpTools.size());

        for (McpTool tool : mcpTools) {
            mcpServerService.registerTool(tool);
            log.info("已注册MCP工具: {} - {}", tool.getName(), tool.getDescription());
        }

        log.info("MCP工具注册完成, 共注册 {} 个工具", mcpServerService.getToolCount());
    }
}
