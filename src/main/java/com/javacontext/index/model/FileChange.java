package com.javacontext.index.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Git变更文件信息
 * 
 * 表示通过git diff检测到的单个文件变更
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileChange {
    
    /**
     * 变更类型
     */
    private ChangeType type;
    
    /**
     * 文件相对路径
     */
    private String filePath;
    
    /**
     * 重命名后的路径 (仅当type为RENAME时有值)
     */
    private String newPath;
    
    /**
     * 变更类型枚举
     */
    public enum ChangeType {
        /** 新增 */
        ADDED,
        /** 修改 */
        MODIFIED,
        /** 删除 */
        DELETED,
        /** 重命名 */
        RENAMED
    }
}
