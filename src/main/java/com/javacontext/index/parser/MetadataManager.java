package com.javacontext.index.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javacontext.index.model.IndexMetadata;
import com.javacontext.index.model.ProjectInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 元数据管理器
 * 
 * 职责:
 * 1. 保存索引元数据到全局目录 ~/.java-context-index/indices/{projectKey}/
 * 2. 加载索引元数据
 * 3. 管理增量更新状态
 * 
 * 存储路径: ~/.java-context-index/indices/{projectKey}/index-metadata.json
 */
@Slf4j
@Component
public class MetadataManager {

    private final ProjectDetector projectDetector;
    private final ObjectMapper objectMapper;

    public MetadataManager(ProjectDetector projectDetector) {
        this.projectDetector = projectDetector;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 保存索引元数据到全局目录
     *
     * @param metadata 索引元数据
     */
    public void saveMetadata(IndexMetadata metadata) {
        Path metadataPath = projectDetector.getMetadataFilePath(metadata.getProjectKey());

        try {
            // 确保目录存在
            Files.createDirectories(metadataPath.getParent());

            // 写入JSON文件
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), metadata);

            log.info("索引元数据已保存: {}, 文件数: {}, 代码块数: {}",
                    metadata.getProjectKey(), metadata.getTotalFiles(), metadata.getTotalChunks());
        } catch (IOException e) {
            log.error("保存索引元数据失败: {}", metadataPath, e);
            throw new RuntimeException("保存索引元数据失败", e);
        }
    }

    /**
     * 加载索引元数据
     *
     * @param projectKey 项目Key
     * @return 索引元数据, 如果不存在则返回null
     */
    public IndexMetadata loadMetadata(String projectKey) {
        Path metadataPath = projectDetector.getMetadataFilePath(projectKey);

        if (!Files.exists(metadataPath)) {
            log.debug("索引元数据不存在: {}", projectKey);
            return null;
        }

        try {
            IndexMetadata metadata = objectMapper.readValue(metadataPath.toFile(), IndexMetadata.class);
            log.debug("加载索引元数据: {}, 上次commit: {}",
                    projectKey, metadata.getLastIndexedCommit());
            return metadata;
        } catch (IOException e) {
            log.error("加载索引元数据失败: {}", metadataPath, e);
            return null;
        }
    }

    /**
     * 检查索引是否存在
     */
    public boolean existsIndex(String projectKey) {
        Path metadataPath = projectDetector.getMetadataFilePath(projectKey);
        return Files.exists(metadataPath);
    }

    /**
     * 创建新的索引元数据
     */
    public IndexMetadata createMetadata(ProjectInfo projectInfo, String indexedBy) {
        return IndexMetadata.builder()
                .version(1)
                .projectKey(projectInfo.getProjectKey())
                .projectName(projectInfo.getProjectName())
                .projectPath(projectInfo.getProjectPath())
                .projectType(projectInfo.getProjectType().name().toLowerCase())
                .lastIndexedCommit(projectInfo.getCurrentCommitHash())
                .baseBranch("main")
                .indexedAt(LocalDateTime.now())
                .indexedBy(indexedBy)
                .totalFiles(0)
                .totalChunks(0)
                .build();
    }

    /**
     * 更新元数据中的文件信息
     */
    public void updateFileInfo(IndexMetadata metadata, String filePath, String commitHash,
                               int chunkCount, List<String> chunkIds) {
        IndexMetadata.FileInfo fileInfo = IndexMetadata.FileInfo.builder()
                .commitHash(commitHash)
                .chunkCount(chunkCount)
                .chunkIds(chunkIds)
                .build();

        metadata.getFiles().put(filePath, fileInfo);
    }

    /**
     * 从元数据中移除文件信息
     */
    public void removeFileInfo(IndexMetadata metadata, String filePath) {
        metadata.getFiles().remove(filePath);
    }

    /**
     * 获取已索引的文件列表
     */
    public List<String> getIndexedFiles(IndexMetadata metadata) {
        return List.copyOf(metadata.getFiles().keySet());
    }

    /**
     * 获取文件的代码块ID列表
     */
    public List<String> getChunkIdsForFile(IndexMetadata metadata, String filePath) {
        IndexMetadata.FileInfo fileInfo = metadata.getFiles().get(filePath);
        if (fileInfo != null) {
            return fileInfo.getChunkIds();
        }
        return List.of();
    }

    /**
     * 更新元数据统计信息
     */
    public void updateStats(IndexMetadata metadata, int totalFiles, int totalChunks) {
        metadata.setTotalFiles(totalFiles);
        metadata.setTotalChunks(totalChunks);
        metadata.setIndexedAt(LocalDateTime.now());
    }

    /**
     * 更新最后一次索引的commit
     */
    public void updateLastCommit(IndexMetadata metadata, String commitHash) {
        metadata.setLastIndexedCommit(commitHash);
        metadata.setIndexedAt(LocalDateTime.now());
    }
}
