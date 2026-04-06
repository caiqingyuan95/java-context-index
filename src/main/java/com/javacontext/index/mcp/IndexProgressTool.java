package com.javacontext.index.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.javacontext.index.embedding.IndexProgressTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP工具: index_progress
 * 
 * 查询索引建立进度
 */
@Slf4j
@Component
public class IndexProgressTool implements McpTool {

    private final IndexProgressTracker progressTracker;

    public IndexProgressTool(IndexProgressTracker progressTracker) {
        this.progressTracker = progressTracker;
    }

    @Override
    public String getName() {
        return "index_progress";
    }

    @Override
    public String getDescription() {
        return "查询索引建立的实时进度,包括总体进度、各阶段进度和预估剩余时间。";
    }

    @Override
    public List<ToolParam> getParameters() {
        return List.of(
                ToolParam.required("taskId", "string", "索引任务ID")
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String taskId = arguments.path("taskId").asText();

            if (taskId == null || taskId.isEmpty()) {
                return "❌ 错误: taskId参数不能为空";
            }

            log.info("MCP工具调用: index_progress, taskId={}", taskId);

            IndexProgressTracker.IndexTask task = progressTracker.getProgress(taskId);
            if (task == null) {
                return "❌ 错误: 任务不存在: " + taskId;
            }

            // 格式化进度信息
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📊 索引建立进度:\n\n"));
            sb.append(String.format("✅ 任务ID: %s\n", task.getTaskId()));
            sb.append(String.format("📦 项目: %s\n", task.getProjectKey()));
            sb.append(String.format("🔄 状态: %s\n\n", translateStatus(task.getStatus().name())));

            sb.append("📈 进度详情:\n");
            sb.append(String.format("   - 总体进度: %.1f%% (%d/%d 文件)\n",
                    task.getPercentage(), task.getProcessedFiles(), task.getTotalFiles()));
            sb.append(String.format("   - 当前阶段: %s\n", translateStage(task.getCurrentStage())));
            sb.append(String.format("   - 已处理: %d个文件\n", task.getProcessedFiles()));
            sb.append(String.format("   - 待处理: %d个文件\n", task.getPendingFiles()));
            sb.append(String.format("   - 已生成代码块: %d个\n", task.getGeneratedChunks()));
            sb.append(String.format("   - 已插入向量: %d个\n\n", task.getInsertedVectors()));

            sb.append("⏱️ 时间统计:\n");
            sb.append(String.format("   - 已耗时: %s\n", formatSeconds(task.getElapsedSeconds())));
            sb.append(String.format("   - 预估剩余: %s\n", formatSeconds(task.getEstimatedRemainingSeconds())));
            
            if (task.getEstimatedCompletionTime() != null) {
                sb.append(String.format("   - 预计完成: %s\n\n", task.getEstimatedCompletionTime()));
            }

            // 阶段进度
            sb.append("📊 阶段进度:\n");
            for (var entry : task.getStages().entrySet()) {
                var stage = entry.getValue();
                String icon = switch (stage.getStatus().name()) {
                    case "COMPLETED" -> "✅";
                    case "IN_PROGRESS" -> "🔄";
                    case "FAILED" -> "❌";
                    default -> "⏳";
                };
                sb.append(String.format("   %s %s: %.1f%% (%d/%d)\n",
                        icon,
                        translateStage(entry.getKey()),
                        stage.getPercentage(),
                        stage.getProcessed(),
                        stage.getTotal()));
            }

            if (task.getErrorMessage() != null) {
                sb.append(String.format("\n❌ 错误信息: %s", task.getErrorMessage()));
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("index_progress执行失败", e);
            return "❌ 错误: " + e.getMessage();
        }
    }

    private String translateStatus(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> "进行中";
            case "COMPLETED" -> "已完成";
            case "FAILED" -> "失败";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

    private String translateStage(String stage) {
        return switch (stage) {
            case "AST_PARSING" -> "AST解析";
            case "SEMANTIC_TEXT_BUILDING" -> "语义文本构建";
            case "EMBEDDING" -> "Embedding向量化";
            case "VECTOR_INSERT" -> "向量数据库插入";
            default -> stage;
        };
    }

    private String formatSeconds(long seconds) {
        if (seconds <= 0) return "计算中...";
        long minutes = seconds / 60;
        long secs = seconds % 60;
        return String.format("%d分%d秒", minutes, secs);
    }
}
