package com.javacontext.index.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 方法调用关系
 * 
 * 记录方法之间的调用关系，用于构建调用链路
 * 例如：Controller.createOrder() → Service.createOrder() → DAO.insert()
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodCallRelation {
    
    /**
     * 调用方ID
     */
    private String callerId;
    
    /**
     * 调用方方法名
     */
    private String callerName;
    
    /**
     * 调用方完全限定名
     */
    private String callerFQN;
    
    /**
     * 被调用方ID
     */
    private String calleeId;
    
    /**
     * 被调用方方法名
     */
    private String calleeName;
    
    /**
     * 被调用方完全限定名
     */
    private String calleeFQN;
    
    /**
     * 调用类型
     * - CONTROLLER_TO_SERVICE: Controller调用Service
     * - SERVICE_TO_DAO: Service调用DAO
     * - SERVICE_TO_SERVICE: Service调用Service
     * - METHOD_CALL: 普通方法调用
     */
    private String callType;
}
