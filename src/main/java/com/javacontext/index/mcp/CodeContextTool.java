package com.javacontext.index.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.javacontext.index.embedding.VectorDatabaseStrategy;
import com.javacontext.index.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP工具: code_context
 * 
 * 获取代码上下文
 */
@Slf4j
@Component
public class CodeContextTool implements McpTool {

    private final VectorDatabaseStrategy vectorDatabase;

    public CodeContextTool(VectorDatabaseStrategy vectorDatabase) {
        this.vectorDatabase = vectorDatabase;
    }

    @Override
    public String getName() {
        return "code_context";
    }

    @Override
    public String getDescription() {
        return "根据代码块ID获取完整的代码及上下文信息。";
    }

    @Override
    public List<ToolParam> getParameters() {
        return List.of(
                ToolParam.required("chunkId", "string", "代码块ID"),
                ToolParam.optional("contextLines", "integer", "上下文行数 (默认10)")
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String chunkId = arguments.path("chunkId").asText();
            int contextLines = arguments.path("contextLines").asInt(10);

            if (chunkId == null || chunkId.isEmpty()) {
                return "❌ 错误: chunkId参数不能为空";
            }

            log.info("MCP工具调用: code_context, chunkId={}", chunkId);

            // 注: 这里简化实现,实际应该从向量数据库根据ID查询
            // 由于VectorDatabaseService没有提供getById方法,这里返回提示信息
            return String.format("""
                    📝 代码上下文查询
                    
                    代码块ID: %s
                    上下文行数: %d
                    
                    ⚠️ 此功能需要从向量数据库查询具体代码块
                    💡 建议使用 code_search 搜索相关代码""",
                    chunkId, contextLines);

        } catch (Exception e) {
            log.error("code_context执行失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }
}
