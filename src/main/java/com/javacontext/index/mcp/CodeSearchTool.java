package com.javacontext.index.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.javacontext.index.model.SearchResponse;
import com.javacontext.index.model.SearchResult;
import com.javacontext.index.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP工具: code_search
 * 
 * 语义搜索代码
 */
@Slf4j
@Component
public class CodeSearchTool implements McpTool {

    private final SearchService searchService;

    public CodeSearchTool(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String getName() {
        return "code_search";
    }

    @Override
    public String getDescription() {
        return "使用自然语言语义搜索Java代码。支持按项目、代码块类型等过滤。";
    }

    @Override
    public List<ToolParam> getParameters() {
        return List.of(
                ToolParam.required("query", "string", "搜索查询 (自然语言)"),
                ToolParam.optional("projectKey", "string", "项目Key (可选)"),
                ToolParam.optional("topK", "integer", "返回结果数量 (默认10)"),
                ToolParam.optional("chunkType", "string", "代码块类型过滤 (METHOD/CLASS/FIELD)"),
                ToolParam.optional("packageName", "string", "包名过滤")
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String query = arguments.path("query").asText();
            String projectKey = arguments.path("projectKey").asText(null);
            int topK = arguments.path("topK").asInt(10);
            String chunkType = arguments.path("chunkType").asText(null);
            String packageName = arguments.path("packageName").asText(null);

            if (query == null || query.isEmpty()) {
                return "❌ 错误: query参数不能为空";
            }

            log.info("MCP工具调用: code_search, query={}, topK={}", query, topK);

            // 执行搜索
            SearchResponse response = searchService.search(query, projectKey, topK);

            if (response.getResults() == null || response.getResults().isEmpty()) {
                return String.format("""
                        🔍 搜索完成,未找到匹配的代码。
                        
                        查询: "%s"
                        耗时: %dms""",
                        query, response.getElapsedMs());
            }

            // 格式化结果
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("🔍 搜索: \"%s\"\n", query));
            sb.append(String.format("📊 找到 %d 个匹配的代码块 (耗时 %dms):\n\n",
                    response.getResults().size(), response.getElapsedMs()));

            List<SearchResult> results = response.getResults();
            for (int i = 0; i < results.size(); i++) {
                SearchResult result = results.get(i);
                sb.append(String.format("%d. **%s** (相似度: %.2f%%)\n",
                        i + 1, result.getFullyQualifiedName() != null ? 
                                result.getFullyQualifiedName() : result.getName(),
                        result.getScore() * 100));
                sb.append(String.format("   📁 文件: %s:%d-%d\n",
                        result.getFilePath(), result.getStartLine(), result.getEndLine()));
                
                if (result.getDescription() != null && !result.getDescription().isEmpty()) {
                    sb.append(String.format("   📝 描述: %s\n", result.getDescription()));
                }
                
                if (result.getCode() != null && !result.getCode().isEmpty()) {
                    String codePreview = result.getCode().length() > 200 ? 
                            result.getCode().substring(0, 200) + "..." : result.getCode();
                    sb.append(String.format("   ```java\n   %s\n   ```\n", codePreview));
                }
                
                sb.append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("code_search执行失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }
}
