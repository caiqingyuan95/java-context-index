package com.javacontext.index.embedding;

import com.javacontext.index.config.AppProperties;
import com.javacontext.index.model.CodeChunk;
import com.javacontext.index.model.FileChange;
import com.javacontext.index.model.IndexMetadata;
import com.javacontext.index.model.ProjectInfo;
import com.javacontext.index.parser.JavaCodeParser;
import com.javacontext.index.parser.MetadataManager;
import com.javacontext.index.parser.ProjectDetector;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 增量索引服务
 * 
 * 职责: 基于Git Diff的增量索引更新
 * 
 * 核心流程:
 * 1. 读取元数据获取lastIndexedCommit
 * 2. Git差分计算获取变更文件
 * 3. 按需同步 (新增/修改/删除/重命名)
 * 4. 更新元数据
 */
@Slf4j
@Service
public class IncrementalIndexService {

    private final ProjectDetector projectDetector;
    private final JavaCodeParser javaCodeParser;
    private final SemanticTextBuilder semanticTextBuilder;
    private final EmbeddingService embeddingService;
    private final VectorDatabaseStrategy vectorDatabase;
    private final MetadataManager metadataManager;
    private final IndexProgressTracker progressTracker;

    public IncrementalIndexService(ProjectDetector projectDetector,
                                    JavaCodeParser javaCodeParser,
                                    SemanticTextBuilder semanticTextBuilder,
                                    EmbeddingService embeddingService,
                                    VectorDatabaseStrategy vectorDatabase,
                                    MetadataManager metadataManager,
                                    IndexProgressTracker progressTracker) {
        this.projectDetector = projectDetector;
        this.javaCodeParser = javaCodeParser;
        this.semanticTextBuilder = semanticTextBuilder;
        this.embeddingService = embeddingService;
        this.vectorDatabase = vectorDatabase;
        this.metadataManager = metadataManager;
        this.progressTracker = progressTracker;
    }

    /**
     * 执行增量索引更新
     *
     * @param projectInfo 项目信息
     * @param indexedBy   索引构建者
     * @return 更新结果统计
     */
    public IncrementalResult performIncrementalUpdate(ProjectInfo projectInfo, String indexedBy) {
        String projectKey = projectInfo.getProjectKey();
        Path projectPath = Paths.get(projectInfo.getProjectPath());

        // 加载已有元数据
        IndexMetadata metadata = metadataManager.loadMetadata(projectKey);
        if (metadata == null) {
            log.warn("未找到索引元数据, 无法执行增量更新: {}", projectKey);
            return IncrementalResult.error("未找到索引元数据, 请先执行全量索引");
        }

        String lastCommit = metadata.getLastIndexedCommit();
        String currentCommit = projectDetector.getCurrentCommitHash(projectPath);

        if (lastCommit == null || currentCommit == null) {
            return IncrementalResult.error("无法获取Git commit信息");
        }

        if (lastCommit.equals(currentCommit)) {
            log.info("代码未变更, 无需更新索引: commit={}", currentCommit);
            return IncrementalResult.success("代码未变更, 索引已是最新", 0, 0, 0, 0);
        }

        log.info("开始增量索引更新: {} -> {}", lastCommit, currentCommit);

        // 创建进度跟踪任务
        String taskId = progressTracker.createTask(projectKey, 100);

        try {
            // 1. 获取变更文件列表
            List<FileChange> changes = getGitDiff(projectPath, lastCommit, currentCommit);
            log.info("检测到 {} 个文件变更", changes.size());

            int addedCount = 0;
            int modifiedCount = 0;
            int deletedCount = 0;
            int renamedCount = 0;

            // 2. 处理变更
            for (int i = 0; i < changes.size(); i++) {
                FileChange change = changes.get(i);

                switch (change.getType()) {
                    case ADDED -> {
                        processAddedFile(projectPath, projectInfo, change.getFilePath());
                        addedCount++;
                    }
                    case MODIFIED -> {
                        processModifiedFile(projectPath, projectInfo, metadata, change.getFilePath());
                        modifiedCount++;
                    }
                    case DELETED -> {
                        processDeletedFile(projectInfo, metadata, change.getFilePath());
                        deletedCount++;
                    }
                    case RENAMED -> {
                        processRenamedFile(projectPath, projectInfo, metadata,
                                change.getFilePath(), change.getNewPath());
                        renamedCount++;
                    }
                }

                // 更新进度
                progressTracker.updateStageProgress(taskId, "EMBEDDING",
                        (int) ((i + 1) * 100.0 / changes.size()));
            }

            // 3. 更新元数据
            metadataManager.updateLastCommit(metadata, currentCommit);
            int totalFiles = metadata.getFiles().size();
            int totalChunks = metadata.getFiles().values().stream()
                    .mapToInt(IndexMetadata.FileInfo::getChunkCount)
                    .sum();
            metadataManager.updateStats(metadata, totalFiles, totalChunks);
            metadataManager.saveMetadata(metadata);

            progressTracker.completeTask(taskId);

            log.info("增量索引更新完成: +{} ~{} -{} R={}, taskId={}",
                    addedCount, modifiedCount, deletedCount, renamedCount, taskId);

            return IncrementalResult.success("增量更新完成", addedCount, modifiedCount,
                    deletedCount, renamedCount);

        } catch (Exception e) {
            progressTracker.failTask(taskId, e.getMessage());
            log.error("增量索引更新失败", e);
            return IncrementalResult.error("更新失败: " + e.getMessage());
        }
    }

    /**
     * 获取Git变更文件列表
     */
    private List<FileChange> getGitDiff(Path projectPath, String fromCommit, String toCommit) {
        List<FileChange> changes = new ArrayList<>();

        try {
            String[] command = {"git", "diff", "--name-status", fromCommit, toCommit};
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    FileChange change = parseGitDiffLine(line.trim());
                    if (change != null) {
                        // 仅处理Java文件
                        if (change.getFilePath().endsWith(".java") ||
                                (change.getNewPath() != null && change.getNewPath().endsWith(".java"))) {
                            changes.add(change);
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("git diff退出码: {}", exitCode);
            }

        } catch (IOException | InterruptedException e) {
            log.error("执行git diff失败", e);
            Thread.currentThread().interrupt();
        }

        return changes;
    }

    /**
     * 解析git diff --name-status输出行
     * 
     * 格式:
     * M\tpath/file.java      (修改)
     * A\tpath/file.java      (新增)
     * D\tpath/file.java      (删除)
     * R100\told\tnew         (重命名)
     */
    private FileChange parseGitDiffLine(String line) {
        if (line.isEmpty()) {
            return null;
        }

        String[] parts = line.split("\t");
        if (parts.length < 2) {
            return null;
        }

        String statusCode = parts[0];
        char statusChar = statusCode.charAt(0);

        FileChange change = new FileChange();

        switch (statusChar) {
            case 'A' -> {
                change.setType(FileChange.ChangeType.ADDED);
                change.setFilePath(parts[1]);
            }
            case 'M' -> {
                change.setType(FileChange.ChangeType.MODIFIED);
                change.setFilePath(parts[1]);
            }
            case 'D' -> {
                change.setType(FileChange.ChangeType.DELETED);
                change.setFilePath(parts[1]);
            }
            case 'R' -> {
                change.setType(FileChange.ChangeType.RENAMED);
                change.setFilePath(parts[1]);
                if (parts.length >= 3) {
                    change.setNewPath(parts[2]);
                }
            }
            default -> {
                return null;
            }
        }

        return change;
    }

    /**
     * 处理新增文件
     */
    private void processAddedFile(Path projectPath, ProjectInfo projectInfo, String filePath) {
        Path fullPath = projectPath.resolve(filePath);
        if (!Files.exists(fullPath)) {
            log.warn("新增文件不存在: {}", filePath);
            return;
        }

        List<CodeChunk> chunks = javaCodeParser.parseFile(fullPath,
                projectInfo.getProjectKey(), projectInfo.getProjectName()).getChunks();

        // 构建语义文本
        for (CodeChunk chunk : chunks) {
            chunk.setSemanticText(semanticTextBuilder.buildSemanticText(chunk));
        }

        // 插入向量数据库
        List<String> ids = vectorDatabase.insertCodeChunks(chunks);

        // 更新元数据
        IndexMetadata metadata = metadataManager.loadMetadata(projectInfo.getProjectKey());
        if (metadata != null) {
            metadataManager.updateFileInfo(metadata, filePath,
                    projectInfo.getCurrentCommitHash(), chunks.size(), ids);
            metadataManager.saveMetadata(metadata);
        }

        log.debug("新增文件索引完成: {}, chunks: {}", filePath, chunks.size());
    }

    /**
     * 处理修改文件
     */
    private void processModifiedFile(Path projectPath, ProjectInfo projectInfo,
                                      IndexMetadata metadata, String filePath) {
        // 删除旧索引
        List<String> oldChunkIds = metadataManager.getChunkIdsForFile(metadata, filePath);
        if (!oldChunkIds.isEmpty()) {
            vectorDatabase.deleteByChunkIds(oldChunkIds);
        }

        // 重新解析并插入
        Path fullPath = projectPath.resolve(filePath);
        if (!Files.exists(fullPath)) {
            log.warn("修改文件不存在: {}", filePath);
            return;
        }

        List<CodeChunk> chunks = javaCodeParser.parseFile(fullPath,
                projectInfo.getProjectKey(), projectInfo.getProjectName()).getChunks();

        for (CodeChunk chunk : chunks) {
            chunk.setSemanticText(semanticTextBuilder.buildSemanticText(chunk));
        }

        List<String> ids = vectorDatabase.insertCodeChunks(chunks);

        // 更新元数据
        metadataManager.updateFileInfo(metadata, filePath,
                projectInfo.getCurrentCommitHash(), chunks.size(), ids);
        metadataManager.saveMetadata(metadata);

        log.debug("修改文件索引完成: {}, chunks: {}", filePath, chunks.size());
    }

    /**
     * 处理删除文件
     */
    private void processDeletedFile(ProjectInfo projectInfo, IndexMetadata metadata, String filePath) {
        // 从向量数据库删除
        vectorDatabase.deleteByFilePath(projectInfo.getProjectKey(), filePath);

        // 从元数据删除
        metadataManager.removeFileInfo(metadata, filePath);
        metadataManager.saveMetadata(metadata);

        log.debug("删除文件索引完成: {}", filePath);
    }

    /**
     * 处理重命名文件
     * 
     * 优化策略:
     * 1. 如果文件内容未变,仅更新file_path字段 (无需重新向量化)
     * 2. 如果文件内容也变了,则删除旧索引并重新建立
     */
    private void processRenamedFile(Path projectPath, ProjectInfo projectInfo,
                                     IndexMetadata metadata, String oldPath, String newPath) {
        String projectKey = projectInfo.getProjectKey();
        
        // 获取旧chunk IDs
        List<String> chunkIds = metadataManager.getChunkIdsForFile(metadata, oldPath);
        
        // 检查新文件是否存在
        Path newFullPath = projectPath.resolve(newPath);
        if (!Files.exists(newFullPath)) {
            log.warn("重命名文件不存在: {}", newPath);
            vectorDatabase.deleteByFilePath(projectKey, oldPath);
            metadataManager.removeFileInfo(metadata, oldPath);
            metadataManager.saveMetadata(metadata);
            return;
        }

        // 检查旧文件是否还存在 (判断是纯重命名还是重命名+修改)
        Path oldFullPath = projectPath.resolve(oldPath);
        boolean isPureRename = !Files.exists(oldFullPath);
        
        if (isPureRename) {
            // 纯重命名: 仅更新file_path字段
            log.info("检测到纯重命名操作: {} -> {}", oldPath, newPath);
            
            try {
                // 解析新文件获取chunks
                List<CodeChunk> newChunks = javaCodeParser.parseFile(newFullPath,
                        projectKey, projectInfo.getProjectName()).getChunks();
                
                // 获取旧文件的chunks用于对比
                IndexMetadata.FileInfo oldFileInfo = metadata.getFiles().get(oldPath);
                if (oldFileInfo != null && oldFileInfo.getChunkCount() == newChunks.size()) {
                    // chunks数量相同,可能是纯重命名
                    // 为了安全,还是重新索引 (因为无法直接更新Milvus的file_path字段)
                    log.debug("重新索引重命名文件: {}", newPath);
                }
                
                // 删除旧索引
                vectorDatabase.deleteByFilePath(projectKey, oldPath);
                
                // 构建语义文本并插入新索引
                for (CodeChunk chunk : newChunks) {
                    chunk.setSemanticText(semanticTextBuilder.buildSemanticText(chunk));
                }
                
                List<String> newIds = vectorDatabase.insertCodeChunks(newChunks);
                
                // 更新元数据
                metadataManager.removeFileInfo(metadata, oldPath);
                metadataManager.updateFileInfo(metadata, newPath,
                        projectInfo.getCurrentCommitHash(), newChunks.size(), newIds);
                metadataManager.saveMetadata(metadata);
                
                log.info("重命名文件索引完成: {} -> {}, chunks: {}", oldPath, newPath, newChunks.size());
                
            } catch (Exception e) {
                log.error("处理重命名文件失败: {} -> {}", oldPath, newPath, e);
                // 失败时清理旧索引
                vectorDatabase.deleteByFilePath(projectKey, oldPath);
                metadataManager.removeFileInfo(metadata, oldPath);
                metadataManager.saveMetadata(metadata);
            }
        } else {
            // 文件还存在,可能是修改+重命名
            log.info("检测到重命名+修改操作: {} -> {}", oldPath, newPath);
            
            // 先删除旧索引
            vectorDatabase.deleteByFilePath(projectKey, oldPath);
            metadataManager.removeFileInfo(metadata, oldPath);
            
            // 重新索引新文件
            List<CodeChunk> chunks = javaCodeParser.parseFile(newFullPath,
                    projectKey, projectInfo.getProjectName()).getChunks();
            
            for (CodeChunk chunk : chunks) {
                chunk.setSemanticText(semanticTextBuilder.buildSemanticText(chunk));
            }
            
            List<String> newIds = vectorDatabase.insertCodeChunks(chunks);
            metadataManager.updateFileInfo(metadata, newPath,
                    projectInfo.getCurrentCommitHash(), chunks.size(), newIds);
            metadataManager.saveMetadata(metadata);
            
            log.info("重命名+修改文件索引完成: {} -> {}, chunks: {}", oldPath, newPath, chunks.size());
        }
    }

    /**
     * 增量更新结果
     */
    @Data
    public static class IncrementalResult {
        private boolean success;
        private String message;
        private int addedFiles;
        private int modifiedFiles;
        private int deletedFiles;
        private int renamedFiles;

        public static IncrementalResult success(String message, int added, int modified,
                                                 int deleted, int renamed) {
            IncrementalResult result = new IncrementalResult();
            result.success = true;
            result.message = message;
            result.addedFiles = added;
            result.modifiedFiles = modified;
            result.deletedFiles = deleted;
            result.renamedFiles = renamed;
            return result;
        }

        public static IncrementalResult error(String message) {
            IncrementalResult result = new IncrementalResult();
            result.success = false;
            result.message = message;
            return result;
        }
    }
}
