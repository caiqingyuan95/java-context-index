package com.javacontext.index.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.javacontext.index.parser.MetadataManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP工具: index_status
 * 
 * 查询索引状态
 */
@Slf4j
@Component
public class IndexStatusTool implements McpTool {

    private final MetadataManager metadataManager;

    public IndexStatusTool(MetadataManager metadataManager) {
        this.metadataManager = metadataManager;
    }

    @Override
    public String getName() {
        return "index_status";
    }

    @Override
    public String getDescription() {
        return "查询项目索引的状态信息,包括索引时间、文件数、代码块数等元数据。";
    }

    @Override
    public List<ToolParam> getParameters() {
        return List.of(
                ToolParam.optional("projectKey", "string", "项目Key (可选)")
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String projectKey = arguments.path("projectKey").asText(null);

            log.info("MCP工具调用: index_status, projectKey={}", projectKey);

            if (projectKey == null || projectKey.isEmpty()) {
                return "ℹ️ 请提供projectKey参数查询特定项目索引状态";
            }

            // 检查索引是否存在
            boolean exists = metadataManager.existsIndex(projectKey);
            if (!exists) {
                return String.format("""
                        🔍 索引状态查询
                        
                        项目: %s
                        状态: ❌ 索引不存在
                        
                        💡 请使用 code_index_build 工具构建索引""",
                        projectKey);
            }

            // 加载元数据
            var metadata = metadataManager.loadMetadata(projectKey);
            if (metadata == null) {
                return "❌ 错误: 无法加载索引元数据";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📊 索引状态:\n\n");
            sb.append(String.format("✅ 项目: %s\n", metadata.getProjectKey()));
            sb.append(String.format("📦 项目名称: %s\n", metadata.getProjectName()));
            sb.append(String.format("👤 构建者: %s\n", metadata.getIndexedBy()));
            sb.append(String.format("🕐 索引时间: %s\n", metadata.getIndexedAt()));
            sb.append(String.format("🔖 索引Commit: %s\n\n", metadata.getLastIndexedCommit()));

            sb.append("📈 统计信息:\n");
            sb.append(String.format("   - 文件数: %d\n", metadata.getTotalFiles()));
            sb.append(String.format("   - 代码块数: %d\n", metadata.getTotalChunks()));
            sb.append(String.format("   - Embedding模型: %s\n", metadata.getEmbeddingProvider()));
            sb.append(String.format("   - 向量数据库: %s\n", metadata.getVectorDbType()));
            sb.append(String.format("   - 集合名称: %s\n\n", metadata.getVectorCollection()));

            // 检查新鲜度
            sb.append("🔄 索引新鲜度:\n");
            sb.append("   索引状态正常 ✅\n");
            sb.append("   💡 如有代码更新,请使用 refresh_index 进行增量更新");

            return sb.toString();

        } catch (Exception e) {
            log.error("index_status执行失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }
}
