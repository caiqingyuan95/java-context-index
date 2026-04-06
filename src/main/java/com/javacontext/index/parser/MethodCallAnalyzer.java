package com.javacontext.index.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.javacontext.index.model.CodeChunk;
import com.javacontext.index.model.MethodCallRelation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 方法调用关系分析器
 * 
 * 职责:
 * 1. 分析方法之间的调用关系
 * 2. 构建调用链路图
 * 3. 支持从Controller到DAO的全链路追踪
 * 
 * 输出:
 * - 每个方法的调用关系（调用了谁）
 * - 每个方法的被调用关系（被谁调用）
 * - 完整的调用链路（如：Controller → Service → DAO）
 */
@Slf4j
@Component
public class MethodCallAnalyzer {

    /**
     * 分析代码块列表，提取方法调用关系
     * 
     * @param chunks 代码块列表
     * @param compilationUnit 编译单元
     * @return 调用关系列表
     */
    public List<MethodCallRelation> analyzeCalls(List<CodeChunk> chunks, CompilationUnit compilationUnit) {
        List<MethodCallRelation> relations = new ArrayList<>();
        
        // 构建方法名到CodeChunk的映射
        Map<String, CodeChunk> methodMap = new HashMap<>();
        for (CodeChunk chunk : chunks) {
            if (chunk.getChunkType() == CodeChunk.ChunkType.METHOD 
                    || chunk.getChunkType() == CodeChunk.ChunkType.CONSTRUCTOR) {
                String key = buildMethodKey(chunk);
                methodMap.put(key, chunk);
            }
        }
        
        // 遍历所有方法，分析调用关系
        for (CodeChunk chunk : chunks) {
            if (chunk.getChunkType() != CodeChunk.ChunkType.METHOD) {
                continue;
            }
            
            String callerKey = buildMethodKey(chunk);
            String callerName = chunk.getName();
            String callerFQN = chunk.getFullyQualifiedName();
            
            // 分析方法体中的调用
            MethodDeclaration methodDecl = findMethodDeclaration(compilationUnit, callerName);
            if (methodDecl != null) {
                Set<String> calledMethods = extractCalledMethods(methodDecl);
                
                for (String calledMethod : calledMethods) {
                    // 查找被调用的方法
                    CodeChunk calleeChunk = methodMap.get(calledMethod);
                    if (calleeChunk != null) {
                        MethodCallRelation relation = MethodCallRelation.builder()
                                .callerId(chunk.getId())
                                .callerName(callerName)
                                .callerFQN(callerFQN)
                                .calleeId(calleeChunk.getId())
                                .calleeName(calleeChunk.getName())
                                .calleeFQN(calleeChunk.getFullyQualifiedName())
                                .callType(determineCallType(chunk, calleeChunk))
                                .build();
                        relations.add(relation);
                    }
                }
            }
        }
        
        log.debug("分析方法调用关系: {} 个调用", relations.size());
        return relations;
    }
    
    /**
     * 构建方法唯一标识
     */
    private String buildMethodKey(CodeChunk chunk) {
        return chunk.getFullyQualifiedName();
    }
    
    /**
     * 查找方法声明
     */
    private MethodDeclaration findMethodDeclaration(CompilationUnit cu, String methodName) {
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            if (method.getNameAsString().equals(methodName)) {
                return method;
            }
        }
        return null;
    }
    
    /**
     * 提取方法中调用的所有方法
     */
    private Set<String> extractCalledMethods(MethodDeclaration methodDecl) {
        Set<String> calledMethods = new HashSet<>();
        
        methodDecl.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr call, Void arg) {
                super.visit(call, arg);
                
                // 提取方法名
                String methodName = call.getNameAsString();
                
                // 如果有作用域（如 object.method()），提取完整限定名
                if (call.getScope().isPresent()) {
                    String scope = call.getScope().get().toString();
                    calledMethods.add(scope + "." + methodName);
                } else {
                    calledMethods.add(methodName);
                }
            }
        }, null);
        
        return calledMethods;
    }
    
    /**
     * 判断调用类型
     */
    private String determineCallType(CodeChunk caller, CodeChunk callee) {
        String callerType = caller.getChunkType().name();
        String calleeType = callee.getChunkType().name();
        
        // Controller → Service
        if (callerType.equals("METHOD") && calleeType.equals("METHOD")) {
            if (isController(caller) && isService(callee)) {
                return "CONTROLLER_TO_SERVICE";
            }
            // Service → DAO
            if (isService(caller) && isDAO(callee)) {
                return "SERVICE_TO_DAO";
            }
            // Service → Service
            if (isService(caller) && isService(callee)) {
                return "SERVICE_TO_SERVICE";
            }
        }
        
        return "METHOD_CALL";
    }
    
    /**
     * 检查是否为Controller方法
     */
    private boolean isController(CodeChunk chunk) {
        String annotations = chunk.getAnnotations();
        return annotations != null && (
                annotations.contains("RestController") 
                || annotations.contains("Controller")
                || annotations.contains("RequestMapping")
        );
    }
    
    /**
     * 检查是否为Service方法
     */
    private boolean isService(CodeChunk chunk) {
        String annotations = chunk.getAnnotations();
        return annotations != null && (
                annotations.contains("Service")
        );
    }
    
    /**
     * 检查是否为DAO方法
     */
    private boolean isDAO(CodeChunk chunk) {
        String annotations = chunk.getAnnotations();
        String fqn = chunk.getFullyQualifiedName();
        return annotations != null && annotations.contains("Repository")
                || fqn != null && (fqn.contains("Mapper") || fqn.contains("DAO") || fqn.contains("Repository"));
    }
}
