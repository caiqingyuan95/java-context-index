package com.javacontext.index.controller;

import com.javacontext.index.graph.Neo4jGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图数据库查询控制器
 * 
 * 提供调用链查询、业务流程查询等图相关API
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final Neo4jGraphService graphService;

    public GraphController(Neo4jGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * 查询方法的完整调用链（向下遍历）
     * GET /api/graph/call-chain?methodFQN=com.wms.api.JfWmsClient#orderCreate
     * 或 GET /api/graph/call-chain/{methodId}
     */
    @GetMapping("/call-chain/{methodId}")
    public ResponseEntity<Map<String, Object>> getCallChainById(
            @PathVariable String methodId,
            @RequestParam(defaultValue = "5") int maxDepth) {
        
        log.info("查询调用链（按ID）: methodId={}, maxDepth={}", methodId, maxDepth);
        
        try {
            List<Neo4jGraphService.CallChainNode> chain = graphService.getCallChainDown(methodId, maxDepth);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of(
                    "methodId", methodId,
                    "maxDepth", maxDepth,
                    "callChain", chain,
                    "totalNodes", chain.size()
            ));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询调用链失败", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "查询失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 通过方法全限定名查询调用链
     * GET /api/graph/call-chain-by-fqn?fqn=com.wms.api.JfWmsClient#orderCreate
     */
    @GetMapping("/call-chain-by-fqn")
    public ResponseEntity<Map<String, Object>> getCallChainByFQN(
            @RequestParam String fqn,
            @RequestParam(defaultValue = "5") int maxDepth) {
        
        log.info("查询调用链（按FQN）: fqn={}, maxDepth={}", fqn, maxDepth);
        
        try {
            List<Neo4jGraphService.CallChainNode> chain = graphService.getCallChainDownByFQN(fqn, maxDepth);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of(
                    "methodFQN", fqn,
                    "maxDepth", maxDepth,
                    "callChain", chain,
                    "totalNodes", chain.size()
            ));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询调用链失败", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "查询失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 查询方法的调用者（向上遍历）
     * GET /api/graph/callers/{methodId}
     */
    @GetMapping("/callers/{methodId}")
    public ResponseEntity<Map<String, Object>> getCallers(
            @PathVariable String methodId,
            @RequestParam(defaultValue = "5") int maxDepth) {
        
        log.info("查询调用者: methodId={}, maxDepth={}", methodId, maxDepth);
        
        try {
            List<Neo4jGraphService.CallChainNode> callers = graphService.getCallers(methodId, maxDepth);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of(
                    "methodId", methodId,
                    "callers", callers,
                    "totalCallers", callers.size()
            ));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询调用者失败", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "查询失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 查询业务流程的完整链路
     * GET /api/graph/business-flow/{businessFlow}
     */
    @GetMapping("/business-flow/{businessFlow}")
    public ResponseEntity<Map<String, Object>> getBusinessFlow(@PathVariable String businessFlow) {
        
        log.info("查询业务流程: {}", businessFlow);
        
        try {
            List<Neo4jGraphService.CallChainNode> chain = graphService.getBusinessFlowChain(businessFlow);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of(
                    "businessFlow", businessFlow,
                    "callChain", chain,
                    "totalNodes", chain.size()
            ));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询业务流程失败", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "查询失败: " + e.getMessage()
            ));
        }
    }
}
