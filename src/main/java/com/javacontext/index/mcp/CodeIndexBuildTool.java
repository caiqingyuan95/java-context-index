package com.javacontext.index.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.javacontext.index.model.ProjectInfo;
import com.javacontext.index.parser.ProjectDetector;
import com.javacontext.index.service.IndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP工具: code_index_build
 * 
 * 构建/更新代码索引
 */
@Slf4j
@Component
public class CodeIndexBuildTool implements McpTool {

    private final ProjectDetector projectDetector;
    private final IndexService indexService;

    public CodeIndexBuildTool(ProjectDetector projectDetector, IndexService indexService) {
        this.projectDetector = projectDetector;
        this.indexService = indexService;
    }

    @Override
    public String getName() {
        return "code_index_build";
    }

    @Override
    public String getDescription() {
        return "构建或更新Java代码索引。支持全量索引和增量索引。";
    }

    @Override
    public List<ToolParam> getParameters() {
        return List.of(
                ToolParam.required("projectPath", "string", "Java项目路径"),
                ToolParam.optional("projectKey", "string", "项目Key (可选,自动从pom.xml提取)"),
                ToolParam.optional("force", "boolean", "是否强制重建索引 (默认false)")
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String projectPath = arguments.path("projectPath").asText();
            String projectKey = arguments.path("projectKey").asText(null);
            boolean force = arguments.path("force").asBoolean(false);

            if (projectPath == null || projectPath.isEmpty()) {
                return "❌ 错误: projectPath参数不能为空";
            }

            log.info("MCP工具调用: code_index_build, path={}, force={}", projectPath, force);

            // 检测项目
            ProjectInfo projectInfo = projectDetector.detectProject(projectPath, projectKey);
            if (projectInfo == null) {
                return "❌ 错误: 未检测到Java项目: " + projectPath;
            }

            // 获取当前用户
            String indexedBy = System.getProperty("user.name", "unknown");

            // 执行索引
            IndexService.IndexResult result = indexService.buildFullIndex(projectInfo, indexedBy, force);

            if (result.isSuccess()) {
                return String.format("""
                        ✅ 索引构建成功!
                        
                        📊 统计信息:
                        - 项目: %s
                        - 文件数: %d
                        - 代码块数: %d
                        - 任务ID: %s
                        
                        💡 索引已就绪,可以使用code_search搜索代码""",
                        projectInfo.getProjectKey(),
                        result.getIndexedFiles(),
                        result.getIndexedChunks(),
                        result.getTaskId());
            } else {
                return "❌ 索引构建失败: " + result.getMessage();
            }

        } catch (Exception e) {
            log.error("code_index_build执行失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }
}
