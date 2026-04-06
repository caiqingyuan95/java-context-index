package com.javacontext.index.parser;

import com.javacontext.index.model.CodeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Java Bean类检测器
 * 
 * 职责: 识别Java Bean类（POJO/DTO/VO等纯数据类）
 * 
 * Java Bean特征:
 * - 只有字段（Field）
 * - 只有getter/setter方法
 * - 没有其他业务逻辑方法
 * - 通常没有复杂注解（如@Service、@Controller等）
 * 
 * 用途: 对于Java Bean类，不为每个getter/setter单独建立索引，
 *       只为整个类建立一个索引，通过Ollama生成类说明
 */
@Slf4j
@Component
public class JavaBeanDetector {

    /**
     * 检查类是否为Java Bean
     * 
     * @param allChunks 该类的所有代码块
     * @return true如果是Java Bean类
     */
    public boolean isJavaBean(List<CodeChunk> allChunks) {
        if (allChunks == null || allChunks.isEmpty()) {
            return false;
        }

        // 获取类级别的代码块
        CodeChunk classChunk = allChunks.stream()
                .filter(c -> c.getChunkType() == CodeChunk.ChunkType.CLASS 
                        || c.getChunkType() == CodeChunk.ChunkType.INTERFACE)
                .findFirst()
                .orElse(null);

        if (classChunk == null) {
            return false;
        }

        // Java Bean类通常没有业务注解
        if (hasBusinessAnnotations(classChunk.getAnnotations())) {
            return false;
        }

        // 分析方法类型
        List<CodeChunk> methods = allChunks.stream()
                .filter(c -> c.getChunkType() == CodeChunk.ChunkType.METHOD)
                .collect(Collectors.toList());

        if (methods.isEmpty()) {
            // 如果只有字段，可能是纯数据类
            return true;
        }

        // 检查所有方法是否都是getter/setter
        boolean allGetterSetter = methods.stream()
                .allMatch(this::isGetterOrSetter);

        if (!allGetterSetter) {
            return false;
        }

        // 计算getter/setter占比
        int totalMembers = methods.size() + (int) allChunks.stream()
                .filter(c -> c.getChunkType() == CodeChunk.ChunkType.FIELD)
                .count();

        if (totalMembers == 0) {
            return false;
        }

        double getterSetterRatio = (double) methods.size() / totalMembers;
        
        // 如果getter/setter占比超过70%，认为是Java Bean
        return getterSetterRatio >= 0.7;
    }

    /**
     * 检查方法是否为getter或setter
     */
    private boolean isGetterOrSetter(CodeChunk method) {
        String name = method.getName();
        if (name == null) {
            return false;
        }

        // Getter: getXxx(), isXxx()
        if (name.startsWith("get") && name.length() > 3) {
            return true;
        }
        if (name.startsWith("is") && name.length() > 2) {
            return true;
        }

        // Setter: setXxx()
        if (name.startsWith("set") && name.length() > 3) {
            return true;
        }

        return false;
    }

    /**
     * 检查是否有业务注解
     */
    private boolean hasBusinessAnnotations(String annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return false;
        }

        Set<String> businessAnnotations = Set.of(
                "Service", "Controller", "RestController", "Repository",
                "Component", "Configuration", "Bean", "Aspect",
                "Interceptor", "Filter", "Listener", "Scheduled",
                "Transactional", "Cacheable", "Async"
        );

        String[] annotationArray = annotations.split(",");
        for (String annotation : annotationArray) {
            if (businessAnnotations.contains(annotation.trim())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 过滤掉getter/setter方法，只保留类和其他重要代码块
     * 
     * @param allChunks 所有代码块
     * @param isBeanClass 是否是Java Bean类
     * @return 过滤后的代码块列表
     */
    public List<CodeChunk> filterChunksForBean(List<CodeChunk> allChunks, boolean isBeanClass) {
        if (!isBeanClass) {
            return allChunks;
        }

        log.debug("Java Bean类检测成功，过滤getter/setter方法");

        // 只保留类级别代码块和字段（可选）
        return allChunks.stream()
                .filter(c -> c.getChunkType() == CodeChunk.ChunkType.CLASS
                        || c.getChunkType() == CodeChunk.ChunkType.INTERFACE
                        || c.getChunkType() == CodeChunk.ChunkType.ENUM
                        || c.getChunkType() == CodeChunk.ChunkType.ANNOTATION
                        || c.getChunkType() == CodeChunk.ChunkType.FIELD)
                .collect(Collectors.toList());
    }
}
