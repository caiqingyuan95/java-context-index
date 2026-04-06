package com.javacontext.index.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 项目信息模型
 * 
 * 表示一个被索引的Java项目的元数据信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectInfo {
    
    /**
     * 项目唯一标识 (如: com.myorg:order-service:1.0.0)
     */
    private String projectKey;
    
    /**
     * 项目名称 (如: order-service)
     */
    private String projectName;
    
    /**
     * 项目根目录路径
     */
    private String projectPath;
    
    /**
     * 项目类型 (maven, gradle)
     */
    private ProjectType projectType;
    
    /**
     * Maven/Gradle groupId
     */
    private String groupId;
    
    /**
     * Maven/Gradle artifactId
     */
    private String artifactId;
    
    /**
     * 项目版本
     */
    private String version;
    
    /**
     * Git远程仓库地址
     */
    private String gitRemoteUrl;
    
    /**
     * 当前Git commit hash
     */
    private String currentCommitHash;
    
    /**
     * 当前分支名称
     */
    private String currentBranch;
    
    /**
     * 项目类型枚举
     */
    public enum ProjectType {
        MAVEN,
        GRADLE,
        UNKNOWN
    }
}
