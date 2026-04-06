package com.javacontext.index.embedding;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 索引建立进度跟踪器
 * 
 * 核心功能:
 * 1. 创建索引任务并跟踪进度
 * 2. 更新各阶段进度
 * 3. 计算预估剩余时间
 * 4. 查询任务进度
 */
@Slf4j
@Service
public class IndexProgressTracker {

    private final Map<String, IndexTask> tasks = new ConcurrentHashMap<>();

    /**
     * 创建新的索引任务
     */
    public String createTask(String projectKey, int totalFiles) {
        String taskId = "task-" + LocalDateTime.now().toString().replaceAll("[:-]", "")
                + "-" + UUID.randomUUID().toString().substring(0, 6);

        IndexTask task = new IndexTask();
        task.setTaskId(taskId);
        task.setProjectKey(projectKey);
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setTotalFiles(totalFiles);
        task.setStartTime(LocalDateTime.now());

        // 初始化各阶段
        task.getStages().put("AST_PARSING", new StageProgress(totalFiles));
        task.getStages().put("SEMANTIC_TEXT_BUILDING", new StageProgress(totalFiles));
        task.getStages().put("EMBEDDING", new StageProgress(totalFiles));
        task.getStages().put("VECTOR_INSERT", new StageProgress(totalFiles));

        tasks.put(taskId, task);

        log.info("创建索引任务: taskId={}, projectKey={}, totalFiles={}",
                taskId, projectKey, totalFiles);

        return taskId;
    }

    /**
     * 更新阶段进度
     */
    public void updateStageProgress(String taskId, String stageName, int processed) {
        IndexTask task = tasks.get(taskId);
        if (task == null) {
            log.warn("任务不存在: {}", taskId);
            return;
        }

        StageProgress stage = task.getStages().get(stageName);
        if (stage != null) {
            stage.setProcessed(processed);
            stage.setPercentage((double) processed / stage.getTotal() * 100);

            if (processed >= stage.getTotal()) {
                stage.setStatus(StageStatus.COMPLETED);
            } else {
                stage.setStatus(StageStatus.IN_PROGRESS);
                task.setCurrentStage(stageName);
            }

            // 更新总体进度
            task.setProcessedFiles(processed);
            task.setPercentage((double) processed / task.getTotalFiles() * 100);

            // 计算预估时间
            calculateEstimatedTime(task);
        }
    }

    /**
     * 更新代码块统计
     */
    public void updateChunkStats(String taskId, int generatedChunks, int insertedVectors) {
        IndexTask task = tasks.get(taskId);
        if (task != null) {
            task.setGeneratedChunks(generatedChunks);
            task.setInsertedVectors(insertedVectors);
        }
    }

    /**
     * 更新模块进度（多模块项目）
     */
    public void updateModuleProgress(String taskId, String moduleName, int totalFiles) {
        IndexTask task = tasks.get(taskId);
        if (task != null) {
            task.setCurrentModule(moduleName);
            task.setModuleTotalFiles(totalFiles);
            log.info("📦 模块进度: {} - {} 个文件", moduleName, totalFiles);
        }
    }

    /**
     * 更新模块文件进度
     */
    public void updateModuleFileProgress(String taskId, String moduleName, int processedFiles) {
        IndexTask task = tasks.get(taskId);
        if (task != null) {
            task.setModuleProcessedFiles(processedFiles);
            if (task.getModuleTotalFiles() > 0) {
                double modulePercentage = (double) processedFiles / task.getModuleTotalFiles() * 100;
                task.setModulePercentage(modulePercentage);
            }
        }
    }

    /**
     * 标记任务完成
     */
    public void completeTask(String taskId) {
        IndexTask task = tasks.get(taskId);
        if (task != null) {
            task.setStatus(TaskStatus.COMPLETED);
            task.setEndTime(LocalDateTime.now());

            Duration duration = Duration.between(task.getStartTime(), task.getEndTime());
            log.info("索引任务完成: taskId={}, duration={}秒",
                    taskId, duration.getSeconds());
        }
    }

    /**
     * 标记任务失败
     */
    public void failTask(String taskId, String errorMessage) {
        IndexTask task = tasks.get(taskId);
        if (task != null) {
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(errorMessage);
            task.setEndTime(LocalDateTime.now());

            log.error("索引任务失败: taskId={}, error={}", taskId, errorMessage);
        }
    }

    /**
     * 查询任务进度
     */
    public IndexTask getProgress(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 查询所有活跃任务
     */
    public Map<String, IndexTask> getActiveTasks() {
        return tasks.entrySet().stream()
                .filter(e -> e.getValue().getStatus() == TaskStatus.IN_PROGRESS)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * 计算预估剩余时间
     */
    private void calculateEstimatedTime(IndexTask task) {
        Duration elapsed = Duration.between(task.getStartTime(), LocalDateTime.now());
        double percentage = task.getPercentage() / 100.0;

        if (percentage > 0.05) { // 至少完成5%才开始估算
            long totalEstimatedSeconds = (long) (elapsed.getSeconds() / percentage);
            long remainingSeconds = totalEstimatedSeconds - elapsed.getSeconds();

            task.setElapsedSeconds(elapsed.getSeconds());
            task.setEstimatedRemainingSeconds(Math.max(0, remainingSeconds));
            task.setEstimatedCompletionTime(
                    LocalDateTime.now().plusSeconds(remainingSeconds));
        }
    }

    /**
     * 索引任务
     */
    @Data
    public static class IndexTask {
        private String taskId;
        private String projectKey;
        private TaskStatus status;
        private String currentStage;

        private int totalFiles;
        private int processedFiles;
        private double percentage;

        private int generatedChunks;
        private int insertedVectors;

        // 多模块项目相关字段
        private String currentModule;
        private int moduleTotalFiles;
        private int moduleProcessedFiles;
        private double modulePercentage;

        private Map<String, StageProgress> stages = new ConcurrentHashMap<>();

        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private long elapsedSeconds;
        private long estimatedRemainingSeconds;
        private LocalDateTime estimatedCompletionTime;

        private String errorMessage;

        public int getPendingFiles() {
            return totalFiles - processedFiles;
        }
    }

    /**
     * 阶段进度
     */
    @Data
    public static class StageProgress {
        private StageStatus status;
        private int total;
        private int processed;
        private double percentage;

        public StageProgress(int total) {
            this.total = total;
            this.status = StageStatus.PENDING;
        }
    }

    /**
     * 任务状态枚举
     */
    public enum TaskStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
    }

    /**
     * 阶段状态枚举
     */
    public enum StageStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED
    }
}
