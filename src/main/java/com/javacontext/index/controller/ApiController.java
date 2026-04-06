package com.javacontext.index.controller;

import com.javacontext.index.embedding.IndexProgressTracker;
import com.javacontext.index.embedding.MilvusVectorDatabaseAdapter;
import com.javacontext.index.model.ProjectInfo;
import com.javacontext.index.model.SearchResponse;
import com.javacontext.index.parser.ProjectDetector;
import com.javacontext.index.service.IndexService;
import com.javacontext.index.service.SearchService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API控制器
 * 
 * 提供索引构建、搜索、进度查询等REST接口
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ProjectDetector projectDetector;
    private final IndexService indexService;
    private final SearchService searchService;
    private final IndexProgressTracker progressTracker;
    private final MilvusVectorDatabaseAdapter milvusAdapter;

    public ApiController(ProjectDetector projectDetector,
                          IndexService indexService,
                          SearchService searchService,
                          IndexProgressTracker progressTracker,
                          MilvusVectorDatabaseAdapter milvusAdapter) {
        this.projectDetector = projectDetector;
        this.indexService = indexService;
        this.searchService = searchService;
        this.progressTracker = progressTracker;
        this.milvusAdapter = milvusAdapter;
    }

    /**
     * 构建/更新索引（异步）
     * POST /api/index/build
     */
    @PostMapping("/index/build")
    public ResponseEntity<Map<String, Object>> buildIndex(@RequestBody IndexRequest request) {
        log.info("收到索引构建请求: {}", request);

        // 检测项目
        String projectPath = request.getProjectPath();
        ProjectInfo projectInfo = projectDetector.detectProject(projectPath, request.getProjectKey());

        if (projectInfo == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "未检测到Java项目: " + projectPath
            ));
        }

        // 获取当前用户
        String indexedBy = System.getProperty("user.name", "unknown");

        // 异步执行索引构建
        String taskId = indexService.buildFullIndexAsync(
                projectInfo, indexedBy, request.isForce());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "索引任务已启动，正在后台执行");
        response.put("taskId", taskId);
        response.put("projectKey", projectInfo.getProjectKey());

        return ResponseEntity.ok(response);
    }

    /**
     * 搜索代码
     * POST /api/index/search
     */
    @PostMapping("/index/search")
    public ResponseEntity<SearchResponse> searchCode(@RequestBody SearchRequest request) {
        log.info("收到搜索请求: query={}", request.getQuery());

        SearchResponse response = searchService.search(
                request.getQuery(),
                request.getProjectKey(),
                request.getTopK() > 0 ? request.getTopK() : 10
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 查询索引进度
     * GET /api/index/progress/{taskId}
     */
    @GetMapping("/index/progress/{taskId}")
    public ResponseEntity<Map<String, Object>> getProgress(@PathVariable String taskId) {
        IndexProgressTracker.IndexTask task = progressTracker.getProgress(taskId);

        if (task == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> progressMap = new HashMap<>();
        progressMap.put("totalFiles", task.getTotalFiles());
        progressMap.put("processedFiles", task.getProcessedFiles());
        progressMap.put("pendingFiles", task.getPendingFiles());
        progressMap.put("percentage", task.getPercentage());
        progressMap.put("generatedChunks", task.getGeneratedChunks());
        progressMap.put("insertedVectors", task.getInsertedVectors());

        Map<String, Object> timeStatsMap = new HashMap<>();
        timeStatsMap.put("elapsedSeconds", task.getElapsedSeconds());
        timeStatsMap.put("estimatedRemainingSeconds", task.getEstimatedRemainingSeconds());

        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("taskId", task.getTaskId());
        dataMap.put("projectKey", task.getProjectKey() != null ? task.getProjectKey() : "");
        dataMap.put("status", task.getStatus() != null ? task.getStatus().name() : "UNKNOWN");
        dataMap.put("progress", progressMap);
        dataMap.put("currentStage", task.getCurrentStage() != null ? task.getCurrentStage() : "");
        dataMap.put("timeStats", timeStatsMap);
        dataMap.put("message", String.format("进度: %.1f%%", task.getPercentage()));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", dataMap);

        return ResponseEntity.ok(response);
    }

    /**
     * 查询活跃任务
     * GET /api/index/progress/active
     */
    @GetMapping("/index/progress/active")
    public ResponseEntity<Map<String, Object>> getActiveTasks() {
        var activeTasks = progressTracker.getActiveTasks();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", Map.of(
                "activeTasks", activeTasks.values().stream().map(task -> Map.of(
                        "taskId", task.getTaskId(),
                        "projectKey", task.getProjectKey(),
                        "status", task.getStatus().name(),
                        "percentage", task.getPercentage(),
                        "currentStage", task.getCurrentStage()
                )).toList(),
                "totalActiveTasks", activeTasks.size()
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * 检测项目信息
     * GET /api/project/detect?path=xxx
     */
    @GetMapping("/project/detect")
    public ResponseEntity<Map<String, Object>> detectProject(@RequestParam String path) {
        ProjectInfo projectInfo = projectDetector.detectProject(path, null);

        if (projectInfo == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "未检测到Java项目: " + path
            ));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "projectKey", projectInfo.getProjectKey(),
                "projectName", projectInfo.getProjectName(),
                "projectType", projectInfo.getProjectType().name(),
                "currentBranch", projectInfo.getCurrentBranch(),
                "currentCommit", projectInfo.getCurrentCommitHash()
        ));
    }

    /**
     * 获取已索引的项目列表
     * GET /api/index/projects
     */
    @GetMapping("/index/projects")
    public ResponseEntity<Map<String, Object>> getIndexedProjects() {
        log.info("获取已索引项目列表");
        
        try {
            // 从Milvus集合中获取项目列表
            String collectionName = "java_code_index_v2";  // 当前使用的集合名
            List<String> projects = milvusAdapter.getProjectsFromCollection(collectionName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of(
                    "collectionName", collectionName,
                    "projects", projects,
                    "totalProjects", projects.size()
            ));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取项目列表失败", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "获取项目列表失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 删除项目索引
     * DELETE /api/index/delete/{projectName}
     */
    @DeleteMapping("/index/delete/{projectName}")
    public ResponseEntity<Map<String, Object>> deleteIndex(@PathVariable String projectName) {
        log.info("收到删除索引请求: projectName={}", projectName);
        
        try {
            // 从Milvus中删除该项目的所有数据
            int deletedCount = milvusAdapter.deleteByProjectName(projectName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "成功删除项目索引: " + projectName);
            response.put("deletedCount", deletedCount);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("删除索引失败: {}", projectName, e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "删除索引失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 删除整个向量集合
     * DELETE /api/index/dropCollection/{collectionName}
     */
    @DeleteMapping("/index/dropCollection/{collectionName}")
    public ResponseEntity<Map<String, Object>> dropCollection(@PathVariable String collectionName) {
        log.info("收到删除集合请求: collectionName={}", collectionName);
        
        try {
            boolean success = milvusAdapter.dropCollection(collectionName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", success);
            response.put("message", success ? "成功删除集合: " + collectionName : "删除集合失败");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("删除集合失败: {}", collectionName, e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "删除集合失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 索引构建请求
     */
    @Data
    public static class IndexRequest {
        private String projectPath;
        private String projectKey;
        private boolean force;
    }

    /**
     * 搜索请求
     */
    @Data
    public static class SearchRequest {
        private String query;
        private String projectKey;
        private int topK;
    }
}
