package com.javacontext.index.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j图数据库配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "graph-db.neo4j")
public class Neo4jConfig {
    
    /**
     * Neo4j连接URI
     */
    private String uri = "bolt://106.52.62.17:7687";
    
    /**
     * 用户名
     */
    private String username = "neo4j";
    
    /**
     * 密码
     */
    private String password = "password123";
    
    /**
     * 数据库名称
     */
    private String database = "neo4j";
    
    /**
     * 连接池最大大小
     */
    private int maxConnectionPoolSize = 50;
}
