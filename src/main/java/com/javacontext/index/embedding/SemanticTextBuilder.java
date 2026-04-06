package com.javacontext.index.embedding;

import com.javacontext.index.config.AppProperties;
import com.javacontext.index.model.CodeChunk;
import com.javacontext.index.parser.JavaBeanDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 语义文本构建器
 * 
 * 目标: 为每个代码块构建适合Embedding的增强语义文本
 * 
 * 优化策略:
 * 1. Java Bean类（POJO/DTO/VO）：
 *    - 跳过getter/setter的单独索引
 *    - 只为类本身建立索引
 *    - 通过Ollama生成类级别说明
 * 
 * 2. 普通业务类：
 *    - 为每个方法单独建立索引
 *    - 调用Ollama生成方法逻辑摘要（如果无注释）
 * 
 * 构建策略:
 * 语义文本 = 上下文信息 + 自然语言描述 + 代码特征 + 逻辑摘要(可选)
 */
@Slf4j
@Component
public class SemanticTextBuilder {

    private final AppProperties properties;
    private final LogicSummaryService logicSummaryService;
    private final CodeIntentService codeIntentService;
    private final JavaBeanDetector javaBeanDetector;

    public SemanticTextBuilder(AppProperties properties, 
                               LogicSummaryService logicSummaryService,
                               CodeIntentService codeIntentService,
                               JavaBeanDetector javaBeanDetector) {
        this.properties = properties;
        this.logicSummaryService = logicSummaryService;
        this.codeIntentService = codeIntentService;
        this.javaBeanDetector = javaBeanDetector;
    }

    /**
     * 为代码块构建增强语义文本
     *
     * @param chunk 代码块
     * @return 增强语义文本,用于Embedding向量化（如果是Java Bean的getter/setter则返回空字符串）
     */
    public String buildSemanticText(CodeChunk chunk) {
        // Java Bean的getter/setter方法跳过索引
        if (isBeanGetterOrSetter(chunk)) {
            log.debug("跳过Java Bean的getter/setter: {}.{}", 
                    chunk.getFullyQualifiedName(), chunk.getName());
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // 1. 包名上下文
        if (chunk.getPackageName() != null && !chunk.getPackageName().isEmpty()) {
            sb.append("Package: ").append(chunk.getPackageName()).append(". ");
        }

        // 2. 代码块类型和名称
        if (chunk.getChunkType() != null) {
            sb.append(chunk.getChunkType().name()).append(": ");
        }
        sb.append(chunk.getName()).append(". ");

        // 3. 完全限定名
        if (chunk.getFullyQualifiedName() != null && !chunk.getFullyQualifiedName().isEmpty()) {
            sb.append("Full name: ").append(chunk.getFullyQualifiedName()).append(". ");
        }

        // 4. Javadoc描述 (核心语义)
        if (chunk.getDescription() != null && !chunk.getDescription().isEmpty()) {
            sb.append("Description: ").append(chunk.getDescription()).append(". ");
        }

        // 5. 方法特有信息
        if (chunk.getChunkType() == CodeChunk.ChunkType.METHOD
                || chunk.getChunkType() == CodeChunk.ChunkType.CONSTRUCTOR) {

            // 参数
            if (chunk.getParameters() != null && !chunk.getParameters().isEmpty()) {
                sb.append("Parameters: ").append(chunk.getParameters()).append(". ");
            }

            // 返回值 (仅方法)
            if (chunk.getChunkType() == CodeChunk.ChunkType.METHOD
                    && chunk.getReturnType() != null && !chunk.getReturnType().isEmpty()) {
                sb.append("Return type: ").append(chunk.getReturnType()).append(". ");
            }

            // 异常声明
            if (chunk.getThrownExceptions() != null && !chunk.getThrownExceptions().isEmpty()) {
                sb.append("Throws: ").append(chunk.getThrownExceptions()).append(". ");
            }
        }

        // 6. 字段类型信息
        if (chunk.getChunkType() == CodeChunk.ChunkType.FIELD
                && chunk.getReturnType() != null && !chunk.getReturnType().isEmpty()) {
            sb.append("Type: ").append(chunk.getReturnType()).append(". ");
        }

        // 7. 注解信息 (框架语义)
        if (chunk.getAnnotations() != null && !chunk.getAnnotations().isEmpty()) {
            sb.append("Annotations: ").append(chunk.getAnnotations()).append(". ");
        }

        // 8. 访问修饰符
        if (chunk.getModifier() != null && !chunk.getModifier().isEmpty()) {
            sb.append("Modifier: ").append(chunk.getModifier()).append(". ");
        }

        // 9. 调用关系信息（增强语义）
        if (chunk.getChunkType() == CodeChunk.ChunkType.METHOD) {
            // 调用谁
            if (chunk.getCalls() != null && !chunk.getCalls().isEmpty()) {
                sb.append("Calls: ").append(String.join(", ", chunk.getCalls())).append(". ");
            }
            // 被谁调用
            if (chunk.getCalledBy() != null && !chunk.getCalledBy().isEmpty()) {
                sb.append("Called by: ").append(String.join(", ", chunk.getCalledBy())).append(". ");
            }
            // 架构层级
            if (chunk.getLayer() != null && !chunk.getLayer().isEmpty()) {
                sb.append("Layer: ").append(chunk.getLayer()).append(". ");
            }
            // 业务流程
            if (chunk.getBusinessFlow() != null && !chunk.getBusinessFlow().isEmpty()) {
                sb.append("Business Flow: ").append(chunk.getBusinessFlow()).append(". ");
            }
        }

        // 10. 逻辑摘要 (针对无注释代码,可选)
        if (shouldGenerateLogicSummary(chunk)) {
            String logicSummary = generateLogicSummary(chunk);
            if (logicSummary != null && !logicSummary.isEmpty()) {
                sb.append("Logic: ").append(logicSummary).append(". ");
            }
        }

        // 10. 代码意图增强
        String intentText = codeIntentService.analyzeAndEnhance(chunk);
        if (intentText != null && !intentText.isEmpty()) {
            sb.append(intentText).append(". ");
        }

        String result = sb.toString().trim();
        log.debug("构建语义文本: {} -> {} 字符", chunk.getName(), result.length());
        return result;
    }

    /**
     * 判断是否应该生成逻辑摘要
     */
    private boolean shouldGenerateLogicSummary(CodeChunk chunk) {
        AppProperties.LogicSummaryConfig config = properties.getSemanticText().getLogicSummary();
        if (!config.isEnabled()) {
            return false;
        }

        // 如果没有Javadoc或描述很短,则生成逻辑摘要
        String description = chunk.getDescription();
        if (description == null || description.isEmpty()) {
            return true;
        }

        AppProperties.TriggerConditionConfig trigger = config.getTriggerCondition();
        if (trigger.isSkipIfHasJavadoc() && !description.isEmpty()) {
            return false;
        }

        return description.length() < trigger.getMinCommentLength();
    }

    /**
     * 生成逻辑摘要
     * 
     * 使用LogicSummaryService调用Ollama小模型生成智能摘要
     * 如果服务未启用或生成失败,则降级为基于代码特征的简单提取
     */
    private String generateLogicSummary(CodeChunk chunk) {
        // 优先使用AI生成的逻辑摘要
        if (logicSummaryService.isEnabled()) {
            String aiSummary = logicSummaryService.generateSummary(chunk);
            if (aiSummary != null && !aiSummary.isEmpty()) {
                return aiSummary;
            }
        }

        // 降级为基于代码特征的简单提取
        return generateSimpleSummary(chunk);
    }

    /**
     * 简单的代码特征提取 (降级方案)
     */
    private String generateSimpleSummary(CodeChunk chunk) {
        String code = chunk.getCode();
        if (code == null || code.isEmpty()) {
            return "";
        }

        // 限制处理的代码行数
        int maxLines = properties.getSemanticText().getLogicSummary()
                .getGeneration().getMaxCodeLines();
        String[] lines = code.split("\n");
        if (lines.length > maxLines) {
            code = String.join("\n", java.util.Arrays.copyOf(lines, maxLines));
        }

        // 简单的代码特征提取
        StringBuilder summary = new StringBuilder();

        // 检测关键模式
        if (code.contains("if (") || code.contains("else")) {
            summary.append("Contains conditional logic. ");
        }
        if (code.contains("for (") || code.contains("while (") || code.contains("forEach")) {
            summary.append("Contains loops. ");
        }
        if (code.contains("try {") || code.contains("catch (")) {
            summary.append("Has exception handling. ");
        }
        if (code.contains("return ")) {
            summary.append("Returns value. ");
        }
        if (code.contains(".save(") || code.contains(".insert(") || code.contains(".update(")) {
            summary.append("Persists data. ");
        }
        if (code.contains(".find") || code.contains(".get") || code.contains(".query")) {
            summary.append("Retrieves data. ");
        }
        if (code.contains("validate") || code.contains("check") || code.contains("verify")) {
            summary.append("Performs validation. ");
        }

        // 限制摘要长度
        int maxLength = properties.getSemanticText().getLogicSummary()
                .getGeneration().getMaxSummaryLength();
        if (summary.length() > maxLength * 5) { // 粗略估算字符数
            return summary.substring(0, Math.min(summary.length(), maxLength * 5));
        }

        return summary.toString().trim();
    }

    /**
     * 判断是否为Java Bean的getter/setter方法
     * 
     * 检测逻辑：
     * 1. 检查方法名是否符合getter/setter命名规范
     * 2. 检查方法体是否简单（只包含return或赋值）
     * 3. 检查方法是否有业务注解
     */
    private boolean isBeanGetterOrSetter(CodeChunk chunk) {
        // 只处理方法类型
        if (chunk.getChunkType() != CodeChunk.ChunkType.METHOD) {
            return false;
        }

        String name = chunk.getName();
        if (name == null) {
            return false;
        }

        // 检查方法名是否符合getter/setter规范
        boolean isGetterSetter = name.startsWith("get") && name.length() > 3
                || name.startsWith("is") && name.length() > 2
                || name.startsWith("set") && name.length() > 3;

        if (!isGetterSetter) {
            return false;
        }

        // 检查是否有业务注解（如果有，说明不是纯Bean）
        if (chunk.getAnnotations() != null && !chunk.getAnnotations().isEmpty()) {
            if (hasBusinessAnnotation(chunk.getAnnotations())) {
                return false;
            }
        }

        // 检查方法体是否简单
        String code = chunk.getCode();
        if (code != null && !code.isEmpty()) {
            // 如果是getter，应该只包含return语句
            if (name.startsWith("get") || name.startsWith("is")) {
                return isSimpleGetter(code);
            }
            // 如果是setter，应该只包含赋值语句
            if (name.startsWith("set")) {
                return isSimpleSetter(code);
            }
        }

        return false;
    }

    /**
     * 检查是否为简单的getter
     */
    private boolean isSimpleGetter(String code) {
        // 简单getter特征：
        // - 只有return语句
        // - 没有复杂逻辑（if/for/while/try等）
        return !code.contains("if (")
                && !code.contains("for (")
                && !code.contains("while (")
                && !code.contains("try {")
                && !code.contains("throw ")
                && code.contains("return ");
    }

    /**
     * 检查是否为简单的setter
     */
    private boolean isSimpleSetter(String code) {
        // 简单setter特征：
        // - 只有赋值语句
        // - 没有复杂逻辑
        return !code.contains("if (")
                && !code.contains("for (")
                && !code.contains("while (")
                && !code.contains("try {")
                && !code.contains("throw ")
                && code.contains("this.");
    }

    /**
     * 检查是否有业务注解
     */
    private boolean hasBusinessAnnotation(String annotations) {
        Set<String> businessAnnotations = Set.of(
                "Transactional", "Cacheable", "Async", "Scheduled",
                "PreAuthorize", "Secured", "RolesAllowed"
        );

        String[] annotationArray = annotations.split(",");
        for (String annotation : annotationArray) {
            if (businessAnnotations.contains(annotation.trim())) {
                return true;
            }
        }

        return false;
    }
}
