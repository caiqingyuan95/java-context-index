package com.javacontext.index;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Java Context Index 应用启动类
 * 
 * 智能Java代码语义索引系统
 * 将代码结构与自然语言业务能力深度关联,实现自然语言搜索代码
 */
@SpringBootApplication
@EnableConfigurationProperties
public class JavaContextIndexApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaContextIndexApplication.class, args);
    }
}
