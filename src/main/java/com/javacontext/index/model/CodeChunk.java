package com.javacontext.index.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 代码块模型 - 表示一个语义化的代码单元
 * 
 * 每个CodeChunk代表一个可独立理解的代码片段,如一个方法、类或字段
 * 用于向量化和语义搜索的基本单位
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeChunk {
    
    /**
     * 代码块唯一标识 (UUID)
     */
    private String id;
    
    /**
     * 项目唯一标识 (如: com.myorg:order-service:1.0.0)
     */
    private String projectKey;
    
    /**
     * 项目名称 (如: order-service)
     */
    private String projectName;
    
    /**
     * Git远程仓库地址
     */
    private String gitRemoteUrl;
    
    /**
     * 索引构建者标识
     */
    private String indexedBy;
    
    /**
     * 是否共享索引
     */
    private Boolean shared;
    
    /**
     * 文件相对路径 (如: src/main/java/UserService.java)
     */
    private String filePath;
    
    /**
     * 代码块类型 (CLASS, METHOD, FIELD, CONSTRUCTOR等)
     */
    private ChunkType chunkType;
    
    /**
     * 代码块名称 (类名/方法名/字段名)
     */
    private String name;
    
    /**
     * 完全限定名 (如: com.example.UserService.createUser)
     */
    private String fullyQualifiedName;
    
    /**
     * 完整代码内容
     */
    private String code;
    
    /**
     * 增强语义文本 (用于Embedding)
     */
    private String semanticText;
    
    /**
     * Javadoc描述
     */
    private String description;
    
    /**
     * 包名 (如: com.example.service)
     */
    private String packageName;
    
    /**
     * 访问修饰符 (public, private, protected, package-private)
     */
    private String modifier;
    
    /**
     * 起始行号
     */
    private int startLine;
    
    /**
     * 结束行号
     */
    private int endLine;
    
    /**
     * 方法参数列表 (仅方法类型)
     */
    private String parameters;
    
    /**
     * 返回值类型 (仅方法类型)
     */
    private String returnType;
    
    /**
     * 注解列表 (如: @Service, @Transactional)
     */
    private String annotations;
    
    /**
     * 异常声明列表 (仅方法类型)
     */
    private String thrownExceptions;
    
    /**
     * 调用的方法列表 (如: OrderMapper.insert, PaymentService.process)
     */
    private List<String> calls;
    
    /**
     * 被哪些方法调用
     */
    private List<String> calledBy;
    
    /**
     * 架构层级 (CONTROLLER/SERVICE/DAO/MAPPER/UTIL)
     */
    private String layer;
    
    /**
     * 所属业务流程 (如: ORDER_CREATE, PAYMENT_PROCESS)
     */
    private String businessFlow;
    
    /**
     * 代码块枚举
     */
    public enum ChunkType {
        CLASS,
        INTERFACE,
        ENUM,
        METHOD,
        CONSTRUCTOR,
        FIELD,
        ANNOTATION;
        
        /**
         * 安全解析枚举值
         * 
         * @param value 字符串值
         * @return 枚举值，如果无效则返回CLASS
         */
        public static ChunkType safeValueOf(String value) {
            if (value == null || value.isEmpty()) {
                return CLASS;
            }
            try {
                return valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                // 对于无效的枚举值，返回默认值
                return CLASS;
            }
        }
    }
}
