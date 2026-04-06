package com.javacontext.index.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.javacontext.index.model.CodeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Java代码AST解析器
 * 
 * 职责:
 * 使用JavaParser对Java源码进行AST级别解析,提取:
 * - 类、接口、枚举、注解类型
 * - 方法、构造函数、字段
 * - Javadoc注释
 * - 方法签名 (参数、返回值、异常)
 * - 注解信息
 * - 包结构和导入
 * 
 * 输出: List<CodeChunk> - 语义化的代码块列表
 */
@Slf4j
@Component
public class JavaCodeParser {

    private final JavaParser javaParser;

    public JavaCodeParser() {
        // 配置JavaParser
        this.javaParser = new JavaParser();
    }

    /**
     * 解析单个Java文件,提取所有代码块
     *
     * @param filePath    Java文件路径
     * @param projectKey  项目唯一标识
     * @param projectName 项目名称
     * @return 解析结果（包含代码块和编译单元）
     */
    public ParseResult parseFile(Path filePath, String projectKey, String projectName) {
        List<CodeChunk> chunks = new ArrayList<>();
        CompilationUnit compilationUnit = null;

        try {
            // 解析Java文件
            compilationUnit = javaParser.parse(filePath).getResult()
                    .orElseThrow(() -> new RuntimeException("解析失败: " + filePath));

            // 提取包名
            String packageName = compilationUnit.getPackageDeclaration()
                    .map(pd -> pd.getName().asString())
                    .orElse("");

            // 提取导入
            List<String> imports = compilationUnit.getImports().stream()
                    .map(i -> i.getName().asString())
                    .collect(Collectors.toList());

            // 获取文件相对路径
            String relativePath = filePath.toString();

            // 解析类型声明 (类、接口、枚举等)
            for (TypeDeclaration<?> type : compilationUnit.getTypes()) {
                chunks.addAll(parseTypeDeclaration(type, packageName, imports,
                        relativePath, projectKey, projectName));
            }

            log.debug("解析文件完成: {}, 提取代码块数: {}", filePath.getFileName(), chunks.size());

        } catch (FileNotFoundException e) {
            log.error("文件不存在: {}", filePath, e);
        } catch (Exception e) {
            log.error("解析Java文件失败: {}", filePath, e);
        }

        return new ParseResult(chunks, compilationUnit);
    }

    /**
     * 解析结果
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ParseResult {
        private List<CodeChunk> chunks;
        private CompilationUnit compilationUnit;
    }

    /**
     * 方法代码最大行数，超过则切割
     */
    private static final int MAX_METHOD_LINES = 1000;

    /**
     * 解析类型声明 (类、接口、枚举、注解)
     * 严格按层次：包名 → 类名 → 方法/字段名 → 行号
     */
    private List<CodeChunk> parseTypeDeclaration(TypeDeclaration<?> type,
                                                  String packageName,
                                                  List<String> imports,
                                                  String filePath,
                                                  String projectKey,
                                                  String projectName) {
        List<CodeChunk> chunks = new ArrayList<>();

        // 1. 确定类型
        CodeChunk.ChunkType chunkType;
        if (type.isClassOrInterfaceDeclaration()) {
            chunkType = type.asClassOrInterfaceDeclaration().isInterface()
                    ? CodeChunk.ChunkType.INTERFACE
                    : CodeChunk.ChunkType.CLASS;
        } else if (type.isEnumDeclaration()) {
            chunkType = CodeChunk.ChunkType.ENUM;
        } else if (type.isAnnotationDeclaration()) {
            chunkType = CodeChunk.ChunkType.ANNOTATION;
        } else {
            chunkType = CodeChunk.ChunkType.CLASS;
        }

        // 2. 完全限定名：包名.类名
        String className = type.getNameAsString();
        String fullyQualifiedName = packageName.isEmpty()
                ? className
                : packageName + "." + className;

        // 3. 获取Javadoc
        String description = extractJavadoc(type);

        // 4. 获取注解
        String annotations = type.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.joining(", "));

        // 5. 类级别代码块（仅包含类声明，不包含方法体）
        CodeChunk typeChunk = CodeChunk.builder()
                .id(UUID.randomUUID().toString())
                .projectKey(projectKey)
                .projectName(projectName)
                .filePath(filePath)
                .chunkType(chunkType)
                .name(className)
                .fullyQualifiedName(fullyQualifiedName)
                .packageName(packageName)
                .description(description)
                .annotations(annotations)
                .modifier(getModifier(type))
                .startLine(type.getBegin().map(pos -> pos.line).orElse(0))
                .endLine(type.getEnd().map(pos -> pos.line).orElse(0))
                .build();
        
        chunks.add(typeChunk);

        // 6. 解析类成员（方法、字段、构造函数）
        List<BodyDeclaration<?>> members = type.getMembers();
        for (BodyDeclaration<?> member : members) {
            if (member.isMethodDeclaration()) {
                // 解析方法，支持大方法切割
                chunks.addAll(parseMethodDeclaration(
                        member.asMethodDeclaration(),
                        packageName,
                        className,
                        fullyQualifiedName,
                        imports,
                        filePath,
                        projectKey,
                        projectName
                ));
            } else if (member.isFieldDeclaration()) {
                // 解析字段
                chunks.addAll(parseFieldDeclaration(
                        member.asFieldDeclaration(),
                        packageName,
                        className,
                        fullyQualifiedName,
                        filePath,
                        projectKey,
                        projectName
                ));
            } else if (member.isConstructorDeclaration()) {
                // 解析构造函数
                chunks.add(parseConstructorDeclaration(
                        member.asConstructorDeclaration(),
                        packageName,
                        className,
                        fullyQualifiedName,
                        filePath,
                        projectKey,
                        projectName
                ));
            }
        }

        return chunks;
    }

    /**
     * 解析方法声明
     * 如果方法代码超过MAX_METHOD_LINES行，则按1000行切割
     */
    private List<CodeChunk> parseMethodDeclaration(MethodDeclaration method,
                                                     String packageName,
                                                     String className,
                                                     String classFQN,
                                                     List<String> imports,
                                                     String filePath,
                                                     String projectKey,
                                                     String projectName) {
        List<CodeChunk> chunks = new ArrayList<>();
        
        String methodName = method.getNameAsString();
        String methodFQN = classFQN + "." + methodName;
        String description = extractJavadoc(method);
        String annotations = method.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.joining(", "));
        String modifier = getModifier(method);
        String parameters = method.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
        String returnType = method.getTypeAsString();
        
        int startLine = method.getBegin().map(pos -> pos.line).orElse(0);
        int endLine = method.getEnd().map(pos -> pos.line).orElse(0);
        int totalLines = endLine - startLine + 1;
        
        // 如果方法代码超过1000行，按1000行切割
        if (totalLines > MAX_METHOD_LINES) {
            String[] lines = method.toString().split("\n");
            int chunkIndex = 0;
            
            for (int i = 0; i < lines.length; i += MAX_METHOD_LINES) {
                int endIndex = Math.min(i + MAX_METHOD_LINES, lines.length);
                String[] chunkLines = java.util.Arrays.copyOfRange(lines, i, endIndex);
                String chunkCode = String.join("\n", chunkLines);
                
                int chunkStartLine = startLine + i;
                int chunkEndLine = startLine + endIndex - 1;
                
                CodeChunk chunk = CodeChunk.builder()
                        .id(UUID.randomUUID().toString())
                        .projectKey(projectKey)
                        .projectName(projectName)
                        .filePath(filePath)
                        .chunkType(CodeChunk.ChunkType.METHOD)
                        .name(methodName + (chunkIndex > 0 ? "_part" + (chunkIndex + 1) : ""))
                        .fullyQualifiedName(methodFQN + (chunkIndex > 0 ? "_part" + (chunkIndex + 1) : ""))
                        .packageName(packageName)
                        .code(chunkCode)
                        .description(chunkIndex == 0 ? description : description + " (Part " + (chunkIndex + 1) + ")")
                        .annotations(annotations)
                        .modifier(modifier)
                        .parameters(parameters)
                        .returnType(returnType)
                        .startLine(chunkStartLine)
                        .endLine(chunkEndLine)
                        .build();
                
                chunks.add(chunk);
                chunkIndex++;
            }
            
            log.debug("方法 {} 代码过长 ({} 行)，切割为 {} 个代码块", methodName, totalLines, chunkIndex);
        } else {
            // 正常方法，不切割
            CodeChunk methodChunk = CodeChunk.builder()
                    .id(UUID.randomUUID().toString())
                    .projectKey(projectKey)
                    .projectName(projectName)
                    .filePath(filePath)
                    .chunkType(CodeChunk.ChunkType.METHOD)
                    .name(methodName)
                    .fullyQualifiedName(methodFQN)
                    .packageName(packageName)
                    .code(method.toString())
                    .description(description)
                    .annotations(annotations)
                    .modifier(modifier)
                    .parameters(parameters)
                    .returnType(returnType)
                    .startLine(startLine)
                    .endLine(endLine)
                    .build();
            
            chunks.add(methodChunk);
        }
        
        return chunks;
    }

    /**
     * 解析字段声明
     */
    private List<CodeChunk> parseFieldDeclaration(FieldDeclaration field,
                                                    String packageName,
                                                    String className,
                                                    String classFQN,
                                                    String filePath,
                                                    String projectKey,
                                                    String projectName) {
        List<CodeChunk> chunks = new ArrayList<>();
        
        String description = extractJavadoc(field);
        String annotations = field.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.joining(", "));
        String modifier = getModifier(field);
        String fieldType = field.getElementType().asString();
        
        // 一个字段声明可能包含多个变量
        for (VariableDeclarator variable : field.getVariables()) {
            String fieldName = variable.getNameAsString();
            String fieldFQN = classFQN + "." + fieldName;
            
            CodeChunk fieldChunk = CodeChunk.builder()
                    .id(UUID.randomUUID().toString())
                    .projectKey(projectKey)
                    .projectName(projectName)
                    .filePath(filePath)
                    .chunkType(CodeChunk.ChunkType.FIELD)
                    .name(fieldName)
                    .fullyQualifiedName(fieldFQN)
                    .packageName(packageName)
                    .code(field.toString())
                    .description(description)
                    .annotations(annotations)
                    .modifier(modifier)
                    .returnType(fieldType)
                    .startLine(field.getBegin().map(pos -> pos.line).orElse(0))
                    .endLine(field.getEnd().map(pos -> pos.line).orElse(0))
                    .build();
            
            chunks.add(fieldChunk);
        }
        
        return chunks;
    }

    /**
     * 解析构造函数
     */
    private CodeChunk parseConstructorDeclaration(ConstructorDeclaration constructor,
                                                    String packageName,
                                                    String className,
                                                    String classFQN,
                                                    String filePath,
                                                    String projectKey,
                                                    String projectName) {
        String description = extractJavadoc(constructor);
        String annotations = constructor.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.joining(", "));
        String modifier = getModifier(constructor);
        String parameters = constructor.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
        
        return CodeChunk.builder()
                .id(UUID.randomUUID().toString())
                .projectKey(projectKey)
                .projectName(projectName)
                .filePath(filePath)
                .chunkType(CodeChunk.ChunkType.CONSTRUCTOR)
                .name(className)
                .fullyQualifiedName(classFQN + ".<init>")
                .packageName(packageName)
                .code(constructor.toString())
                .description(description)
                .annotations(annotations)
                .modifier(modifier)
                .parameters(parameters)
                .startLine(constructor.getBegin().map(pos -> pos.line).orElse(0))
                .endLine(constructor.getEnd().map(pos -> pos.line).orElse(0))
                .build();
    }

    /**
     * 提取Javadoc描述
     */
    private String extractJavadoc(NodeWithJavadoc<?> node) {
        Optional<String> javadoc = node.getJavadoc()
                .map(j -> j.getDescription().toText());

        return javadoc.orElse("").trim();
    }

    /**
     * 获取修饰符文本
     */
    private String getModifier(BodyDeclaration<?> declaration) {
        if (declaration instanceof com.github.javaparser.ast.nodeTypes.NodeWithModifiers<?>) {
            @SuppressWarnings("unchecked")
            com.github.javaparser.ast.nodeTypes.NodeWithModifiers<?> nodeWithModifiers = 
                    (com.github.javaparser.ast.nodeTypes.NodeWithModifiers<?>) declaration;
            return nodeWithModifiers.getModifiers().stream()
                    .map(m -> m.getKeyword().name().toLowerCase())
                    .collect(Collectors.joining(" "));
        }
        return "";
    }
}
