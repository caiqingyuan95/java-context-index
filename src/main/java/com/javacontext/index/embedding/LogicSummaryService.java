package com.javacontext.index.embedding;

import com.javacontext.index.config.AppProperties;
import com.javacontext.index.model.CodeChunk;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 逻辑摘要生成服务
 * 
 * 针对无注释或注释不足的代码,自动生成逻辑摘要
 * 使用本地Ollama运行的小模型 (如qwen:1.5b-chat)
 * 
 * 触发条件:
 * - 注释长度 < 20字
 * - 没有完整的Javadoc
 * - 代码行数 < 500行 (避免处理过大方法)
 */
@Slf4j
@Service
public class LogicSummaryService {

    private final AppProperties properties;
    private final OllamaChatModel ollamaModel;
    private final boolean enabled;

    public LogicSummaryService(AppProperties properties) {
        this.properties = properties;
        
        AppProperties.LogicSummaryConfig config = properties.getSemanticText().getLogicSummary();
        this.enabled = config.isEnabled();
        
        if (enabled) {
            log.info("初始化逻辑摘要生成服务: provider={}, model={}", 
                    config.getProvider(), config.getModel());
            
            // 初始化Ollama模型
            String baseUrl = config.getOllama().getBaseUrl();
            String modelName = config.getOllama().getModelName();
            
            this.ollamaModel = OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
            
            log.info("逻辑摘要生成服务初始化完成");
        } else {
            log.info("逻辑摘要生成服务已禁用");
            this.ollamaModel = null;
        }
    }

    /**
     * 为代码块生成逻辑摘要
     * 
     * @param chunk 代码块
     * @return 逻辑摘要文本,如果不需要生成则返回null
     */
    public String generateSummary(CodeChunk chunk) {
        if (!enabled) {
            return null;
        }

        // 检查是否需要生成摘要
        if (!shouldGenerateSummary(chunk)) {
            return null;
        }

        try {
            AppProperties.LogicSummaryConfig config = properties.getSemanticText().getLogicSummary();
            
            // 构建提示词
            String prompt = buildPrompt(chunk);
            
            // 调用Ollama生成摘要
            String response = ollamaModel.generate(prompt);
            
            // 清理响应结果
            String summary = cleanSummary(response, config.getGeneration().getMaxSummaryLength());
            
            log.debug("为代码块 {} 生成逻辑摘要: {}", chunk.getId(), summary);
            return summary;
            
        } catch (Exception e) {
            log.warn("生成逻辑摘要失败: chunkId={}", chunk.getId(), e);
            return null;
        }
    }

    /**
     * 检查是否需要生成摘要
     */
    private boolean shouldGenerateSummary(CodeChunk chunk) {
        // 仅处理方法类型
        if (!"METHOD".equals(chunk.getChunkType())) {
            return false;
        }

        // 检查代码行数
        int codeLines = chunk.getEndLine() - chunk.getStartLine() + 1;
        AppProperties.LogicSummaryConfig config = properties.getSemanticText().getLogicSummary();
        if (codeLines > config.getGeneration().getMaxCodeLines()) {
            return false;
        }

        // 检查是否有Javadoc
        String description = chunk.getDescription();
        if (description != null && !description.trim().isEmpty()) {
            // 如果有完整Javadoc,不需要生成
            if (config.getTriggerCondition().isSkipIfHasJavadoc()) {
                return false;
            }
        }

        // 检查注释长度
        if (description != null && description.length() >= config.getTriggerCondition().getMinCommentLength()) {
            return false;
        }

        return true;
    }

    /**
     * 构建提示词
     */
    private String buildPrompt(CodeChunk chunk) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("请用一句话(不超过50字)简要说明以下代码的主要功能和作用:\n\n");
        
        // 添加方法签名信息
        if (chunk.getPackageName() != null) {
            prompt.append("包名: ").append(chunk.getPackageName()).append("\n");
        }
        if (chunk.getName() != null) {
            prompt.append("方法名: ").append(chunk.getName()).append("\n");
        }
        if (chunk.getFullyQualifiedName() != null) {
            prompt.append("全限定名: ").append(chunk.getFullyQualifiedName()).append("\n");
        }
        
        prompt.append("\n代码片段:\n");
        prompt.append("```java\n");
        
        // 只取前500行代码
        String code = chunk.getCode();
        if (code != null) {
            String[] lines = code.split("\n");
            int maxLines = Math.min(lines.length, 50);
            for (int i = 0; i < maxLines; i++) {
                prompt.append(lines[i]).append("\n");
            }
            if (lines.length > 50) {
                prompt.append("... (省略剩余代码)\n");
            }
        }
        
        prompt.append("```\n\n");
        prompt.append("请直接输出功能说明,不要包含其他内容:");
        
        return prompt.toString();
    }

    /**
     * 清理摘要文本
     */
    private String cleanSummary(String summary, int maxLength) {
        if (summary == null) {
            return null;
        }

        // 去除首尾空白
        summary = summary.trim();
        
        // 去除可能的引号
        if (summary.startsWith("\"") && summary.endsWith("\"")) {
            summary = summary.substring(1, summary.length() - 1);
        }
        
        // 限制长度
        if (summary.length() > maxLength) {
            summary = summary.substring(0, maxLength) + "...";
        }
        
        return summary;
    }

    /**
     * 检查服务是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }
}
