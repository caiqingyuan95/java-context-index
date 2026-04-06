package com.javacontext.index.graph;

import com.javacontext.index.config.Neo4jConfig;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.*;
import org.neo4j.driver.types.Node;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;

/**
 * Neo4j图数据库服务
 * 
 * 职责:
 * 1. 存储方法调用关系图
 * 2. 支持图遍历查询调用链
 * 3. 向上/向下查找方法依赖
 */
@Slf4j
@Service
public class Neo4jGraphService {

    private final Neo4jConfig config;
    private Driver driver;

    public Neo4jGraphService(Neo4jConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        driver = GraphDatabase.driver(
                config.getUri(),
                AuthTokens.basic(config.getUsername(), config.getPassword()),
                Config.builder()
                        .withMaxConnectionPoolSize(config.getMaxConnectionPoolSize())
                        .build()
        );

        // 测试连接
        try (Session session = driver.session()) {
            session.run("RETURN 1").consume();
            log.info("✅ Neo4j连接成功: {}", config.getUri());
        } catch (Exception e) {
            log.error("❌ Neo4j连接失败: {}", config.getUri(), e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (driver != null) {
            driver.close();
        }
    }

    /**
     * 插入方法节点
     */
    public void insertMethodNode(String id, String name, String fullyQualifiedName,
                                  String className, String packageName, String layer,
                                  String returnType, String parameters, String annotations,
                                  String description, String businessFlow) {
        String cypher = """
                MERGE (m:Method {id: $id})
                SET m.name = $name,
                    m.fullyQualifiedName = $fullyQualifiedName,
                    m.className = $className,
                    m.packageName = $packageName,
                    m.layer = $layer,
                    m.returnType = $returnType,
                    m.parameters = $parameters,
                    m.annotations = $annotations,
                    m.description = $description,
                    m.businessFlow = $businessFlow,
                    m.updatedAt = datetime()
                """;

        try (Session session = driver.session()) {
            session.run(cypher, Values.parameters(
                    "id", id,
                    "name", name,
                    "fullyQualifiedName", fullyQualifiedName,
                    "className", className,
                    "packageName", packageName,
                    "layer", layer,
                    "returnType", returnType,
                    "parameters", parameters,
                    "annotations", annotations,
                    "description", description,
                    "businessFlow", businessFlow
            ));
        }
    }

    /**
     * 插入调用关系边
     */
    public void insertCallEdge(String callerId, String calleeId, String callType, int callLine) {
        String cypher = """
                MATCH (caller:Method {id: $callerId})
                MATCH (callee:Method {id: $calleeId})
                MERGE (caller)-[r:CALLS]->(callee)
                SET r.type = $callType,
                    r.callLine = $callLine,
                    r.updatedAt = datetime()
                """;

        try (Session session = driver.session()) {
            session.run(cypher, Values.parameters(
                    "callerId", callerId,
                    "calleeId", calleeId,
                    "callType", callType,
                    "callLine", callLine
            ));
        }
    }

    /**
     * 查询方法的完整调用链（向下遍历）
     * 
     * @param methodId 方法ID
     * @param maxDepth 最大深度（默认5层）
     * @return 调用链路
     */
    public List<CallChainNode> getCallChainDown(String methodId, int maxDepth) {
        // Neo4j不允许在关系模式中使用参数，需要动态构建查询
        String cypher = String.format("""
                MATCH path = (start:Method {id: '%s'})-[:CALLS*0..%d]->(end:Method)
                WHERE NOT (end)-[:CALLS]->() OR length(path) = 0
                WITH path, length(path) as depth
                ORDER BY depth ASC
                LIMIT 100
                UNWIND nodes(path) as node
                RETURN DISTINCT node, 
                       [rel in relationships(path) WHERE endNode(rel) = node | startNode(rel)] as callers
                """, methodId.replace("'", "\\'"), maxDepth);

        List<CallChainNode> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        try (Session session = driver.session()) {
            Result result = session.run(cypher);

            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                Node node = record.get("node").asNode();
                
                String nodeId = node.get("id").asString();
                if (visited.add(nodeId)) {
                    chain.add(CallChainNode.builder()
                            .id(nodeId)
                            .name(node.get("name").asString())
                            .fullyQualifiedName(node.get("fullyQualifiedName").asString())
                            .layer(node.get("layer").asString())
                            .description(node.get("description").asString(""))
                            .build());
                }
            }
        }

        return chain;
    }

    /**
     * 通过方法全限定名查询调用链（向下遍历）
     * 
     * @param methodFQN 方法全限定名 (如: com.wms.api.JfWmsClient#orderCreate)
     * @param maxDepth 最大深度（默认5层）
     * @return 调用链路
     */
    public List<CallChainNode> getCallChainDownByFQN(String methodFQN, int maxDepth) {
        // 将 # 替换为 . 以匹配存储的格式
        String normalizedFQN = methodFQN.replace('#', '.');
        
        // Neo4j不允许在关系模式中使用参数，需要动态构建查询
        String cypher = String.format("""
                MATCH path = (start:Method {fullyQualifiedName: '%s'})-[:CALLS*0..%d]->(end:Method)
                WHERE NOT (end)-[:CALLS]->() OR length(path) = 0
                WITH path, length(path) as depth
                ORDER BY depth ASC
                LIMIT 100
                UNWIND nodes(path) as node
                RETURN DISTINCT node, 
                       [rel in relationships(path) WHERE endNode(rel) = node | startNode(rel)] as callers
                """, normalizedFQN.replace("'", "\\'"), maxDepth);

        List<CallChainNode> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        try (Session session = driver.session()) {
            Result result = session.run(cypher);

            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                Node node = record.get("node").asNode();
                
                String nodeId = node.get("id").asString();
                if (visited.add(nodeId)) {
                    chain.add(CallChainNode.builder()
                            .id(nodeId)
                            .name(node.get("name").asString())
                            .fullyQualifiedName(node.get("fullyQualifiedName").asString())
                            .layer(node.get("layer").asString())
                            .description(node.get("description").asString(""))
                            .build());
                }
            }
        }

        return chain;
    }

    /**
     * 查询方法的调用者（向上遍历）
     */
    public List<CallChainNode> getCallers(String methodId, int maxDepth) {
        // Neo4j不允许在关系模式中使用参数，需要动态构建查询
        String cypher = String.format("""
                MATCH path = (caller:Method)-[:CALLS*1..%d]->(target:Method {id: '%s'})
                WHERE NOT (caller)<-[:CALLS]-() OR length(path) = 1
                WITH DISTINCT caller
                RETURN caller
                ORDER BY caller.layer DESC
                """, maxDepth, methodId.replace("'", "\\'"));

        List<CallChainNode> callers = new ArrayList<>();

        try (Session session = driver.session()) {
            Result result = session.run(cypher);

            while (result.hasNext()) {
                Node node = result.next().get("caller").asNode();
                callers.add(CallChainNode.builder()
                        .id(node.get("id").asString())
                        .name(node.get("name").asString())
                        .fullyQualifiedName(node.get("fullyQualifiedName").asString())
                        .layer(node.get("layer").asString())
                        .description(node.get("description").asString(""))
                        .build());
            }
        }

        return callers;
    }

    /**
     * 查询业务流程的完整链路
     */
    public List<CallChainNode> getBusinessFlowChain(String businessFlow) {
        String cypher = String.format("""
                MATCH (controller:Method {layer: 'CONTROLLER', businessFlow: '%s'})
                MATCH path = (controller)-[:CALLS*0..5]->(leaf:Method)
                WHERE NOT (leaf)-[:CALLS]->() OR leaf = controller
                WITH path, length(path) as depth
                ORDER BY depth ASC
                LIMIT 50
                UNWIND nodes(path) as node
                RETURN DISTINCT node
                ORDER BY node.layer DESC, node.name
                """, businessFlow.replace("'", "\\'"));

        List<CallChainNode> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        try (Session session = driver.session()) {
            Result result = session.run(cypher);

            while (result.hasNext()) {
                Node node = result.next().get("node").asNode();
                String nodeId = node.get("id").asString();
                if (visited.add(nodeId)) {
                    chain.add(CallChainNode.builder()
                            .id(nodeId)
                            .name(node.get("name").asString())
                            .fullyQualifiedName(node.get("fullyQualifiedName").asString())
                            .layer(node.get("layer").asString())
                            .description(node.get("description").asString(""))
                            .build());
                }
            }
        }

        return chain;
    }

    /**
     * 调用链节点
     */
    @lombok.Data
    @lombok.Builder
    public static class CallChainNode {
        private String id;
        private String name;
        private String fullyQualifiedName;
        private String layer;
        private String description;
    }
}
