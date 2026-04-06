package com.javacontext.index.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.javacontext.index.embedding.IncrementalIndexService;
import com.javacontext.index.parser.ProjectDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP工具: index_diff
 * 
 * 查看代码变更差异
 */
@Slf4j
@Component
public class IndexDiffTool implements McpTool {

    private final ProjectDetector projectDetector;

    public IndexDiffTool(ProjectDetector projectDetector) {
        this.projectDetector = projectDetector;
    }

    @Override
    public String getName() {
        return "index_diff";
    }

    @Override
    public String getDescription() {
        return "查看两个Git commit之间的代码变更差异。";
    }

    @Override
    public List<ToolParam> getParameters() {
        return List.of(
                ToolParam.required("projectPath", "string", "项目路径"),
                ToolParam.optional("fromCommit", "string", "起始commit (默认上次索引的commit)"),
                ToolParam.optional("toCommit", "string", "结束commit (默认HEAD)")
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String projectPath = arguments.path("projectPath").asText();
            String fromCommit = arguments.path("fromCommit").asText(null);
            String toCommit = arguments.path("toCommit").asText(null);

            if (projectPath == null || projectPath.isEmpty()) {
                return "❌ 错误: projectPath参数不能为空";
            }

            log.info("MCP工具调用: index_diff, path={}", projectPath);

            // 获取当前commit
            if (toCommit == null) {
                toCommit = projectDetector.getCurrentCommitHash(Paths.get(projectPath));
            }

            if (fromCommit == null || toCommit == null) {
                return "❌ 错误: 无法获取commit信息,请确保项目在Git管理下";
            }

            // 执行git diff
            List<String> changes = getGitDiff(Paths.get(projectPath), fromCommit, toCommit);

            if (changes.isEmpty()) {
                return String.format("""
                        🔍 代码变更差异
                        
                        范围: %s → %s
                        
                        ✅ 无文件变更""",
                        fromCommit.substring(0, 7), toCommit.substring(0, 7));
            }

            // 格式化结果
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("🔍 代码变更差异 (%s → %s):\n\n",
                    fromCommit.substring(0, 7), toCommit.substring(0, 7)));

            int added = 0, modified = 0, deleted = 0, renamed = 0;

            for (String change : changes) {
                String[] parts = change.split("\t");
                String status = parts[0];
                char statusChar = status.charAt(0);

                switch (statusChar) {
                    case 'A' -> {
                        added++;
                        sb.append(String.format("➕ [新增] %s\n", parts[1]));
                    }
                    case 'M' -> {
                        modified++;
                        sb.append(String.format("✏️ [修改] %s\n", parts[1]));
                    }
                    case 'D' -> {
                        deleted++;
                        sb.append(String.format("➖ [删除] %s\n", parts[1]));
                    }
                    case 'R' -> {
                        renamed++;
                        sb.append(String.format("🔄 [重命名] %s → %s\n", parts[1], parts[2]));
                    }
                }
            }

            sb.append(String.format("\n📊 统计: 新增=%d, 修改=%d, 删除=%d, 重命名=%d",
                    added, modified, deleted, renamed));
            sb.append("\n\n💡 使用 refresh_index 进行增量更新");

            return sb.toString();

        } catch (Exception e) {
            log.error("index_diff执行失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }

    private List<String> getGitDiff(Path projectPath, String fromCommit, String toCommit) {
        List<String> changes = new ArrayList<>();

        try {
            String[] command = {"git", "diff", "--name-status", fromCommit, toCommit};
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        changes.add(trimmed);
                    }
                }
            }

            process.waitFor();

        } catch (Exception e) {
            log.error("执行git diff失败", e);
        }

        return changes;
    }
}
