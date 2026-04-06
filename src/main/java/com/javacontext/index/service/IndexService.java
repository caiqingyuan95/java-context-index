package com.javacontext.index.service;

import com.javacontext.index.config.AppProperties;
import com.javacontext.index.embedding.EmbeddingService;
import com.javacontext.index.embedding.IndexProgressTracker;
import com.javacontext.index.embedding.IncrementalIndexService;
import com.javacontext.index.embedding.SemanticTextBuilder;
import com.javacontext.index.embedding.VectorDatabaseStrategy;
import com.javacontext.index.graph.Neo4jGraphService;
import com.javacontext.index.model.CodeChunk;
import com.javacontext.index.model.IndexMetadata;
import com.javacontext.index.model.ProjectInfo;
import com.javacontext.index.parser.JavaBeanDetector;
import com.javacontext.index.parser.JavaCodeParser;
import com.javacontext.index.parser.MetadataManager;
import com.javacontext.index.parser.MethodCallAnalyzer;
import com.javacontext.index.parser.ProjectDetector;
import com.javacontext.index.model.MethodCallRelation;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 索引服务
 * 
 * 职责:
 * 1. 全量索引构建
 * 2. 增量索引更新 (调用IncrementalIndexService)
 * 3. 索引状态查询
 */
@Slf4j
@Service
public class IndexService {

    private final AppProperties properties;
    private final ProjectDetector projectDetector;
    private final JavaCodeParser javaCodeParser;
    private final SemanticTextBuilder semanticTextBuilder;
    private final EmbeddingService embeddingService;
    private final VectorDatabaseStrategy vectorDatabase;
    private final MetadataManager metadataManager;
    private final IncrementalIndexService incrementalIndexService;
    private final IndexProgressTracker progressTracker;
    private final JavaBeanDetector javaBeanDetector;
    private final MethodCallAnalyzer methodCallAnalyzer;
    private final Neo4jGraphService neo4jGraphService;

    public IndexService(AppProperties properties,
                         ProjectDetector projectDetector,
                         JavaCodeParser javaCodeParser,
                         SemanticTextBuilder semanticTextBuilder,
                         EmbeddingService embeddingService,
                         VectorDatabaseStrategy vectorDatabase,
                         MetadataManager metadataManager,
                         IncrementalIndexService incrementalIndexService,
                         IndexProgressTracker progressTracker,
                         JavaBeanDetector javaBeanDetector,
                         MethodCallAnalyzer methodCallAnalyzer,
                         Neo4jGraphService neo4jGraphService) {
        this.properties = properties;
        this.projectDetector = projectDetector;
        this.javaCodeParser = javaCodeParser;
        this.semanticTextBuilder = semanticTextBuilder;
        this.embeddingService = embeddingService;
        this.vectorDatabase = vectorDatabase;
        this.metadataManager = metadataManager;
        this.incrementalIndexService = incrementalIndexService;
        this.progressTracker = progressTracker;
        this.javaBeanDetector = javaBeanDetector;
        this.methodCallAnalyzer = methodCallAnalyzer;
        this.neo4jGraphService = neo4jGraphService;
    }

    /**
     * 构建全量索引
     *
     * @param projectInfo 项目信息
     * @param indexedBy   索引构建者
     * @param force       是否强制重建 (删除已有索引)
     * @return 索引结果
     */
    /**
     * 异步构建完整索引
     * 
     * @param projectInfo 项目信息
     * @param indexedBy 索引构建者
     * @param force 是否强制重建
     * @return taskId 任务ID
     */
    public String buildFullIndexAsync(ProjectInfo projectInfo, String indexedBy, boolean force) {
        String projectKey = projectInfo.getProjectKey();
        Path projectPath = Paths.get(projectInfo.getProjectPath());
        
        // 扫描文件数以创建taskId
        List<Path> javaFiles = scanJavaFiles(projectPath);
        int totalFiles = javaFiles.size();
        
        // 创建进度跟踪任务
        String taskId = progressTracker.createTask(projectKey, totalFiles);
        
        // 在新线程中执行索引构建
        new Thread(() -> {
            try {
                log.info("异步索引构建开始: taskId={}, projectKey={}, files={}", taskId, projectKey, totalFiles);
                
                // 直接执行索引构建逻辑，不再调用buildFullIndex（它会再次createTask）
                executeIndexBuild(projectInfo, indexedBy, force, taskId, totalFiles);
                
                log.info("异步索引构建完成: taskId={}", taskId);
            } catch (Exception e) {
                log.error("异步索引构建失败: taskId={}", taskId, e);
                progressTracker.failTask(taskId, e.getMessage());
            }
        }, "index-build-" + taskId).start();
        
        return taskId;
    }

    /**
     * 执行索引构建的核心逻辑
     */
    private void executeIndexBuild(ProjectInfo projectInfo, String indexedBy, boolean force, 
                                   String taskId, int totalFiles) {
        // 这里复用buildFullIndex的大部分逻辑，只是去掉createTask调用
        String projectKey = projectInfo.getProjectKey();
        Path projectPath = Paths.get(projectInfo.getProjectPath());
        
        log.info("开始全量索引构建: projectKey={}, force={}, taskId={}", projectKey, force, taskId);

        // 如果强制重建,先删除已有索引
        if (force) {
            log.info("强制重建, 删除已有索引: {}", projectKey);
            vectorDatabase.deleteByProjectKey(projectKey);
        }

        // 检查是否已有完整索引
        if (!force && metadataManager.existsIndex(projectKey)) {
            IndexMetadata existingMetadata = metadataManager.loadMetadata(projectKey);
            
            if (existingMetadata.getIndexComplete() != null && existingMetadata.getIndexComplete()) {
                log.info("索引已存在且完整，执行增量更新: {}", projectKey);
                IncrementalIndexService.IncrementalResult result =
                        incrementalIndexService.performIncrementalUpdate(projectInfo, indexedBy);
                progressTracker.completeTask(taskId);
                return;
            } else {
                log.warn("索引存在但不完整，将执行全量重建: {}", projectKey);
                vectorDatabase.deleteByProjectKey(projectKey);
            }
        }

        // 创建新元数据
        IndexMetadata metadata = metadataManager.createMetadata(projectInfo, indexedBy);
        metadata.setEmbeddingProvider(properties.getEmbedding().getProvider());
        metadata.setVectorDbType(properties.getVectorDb().getType());
        metadata.setVectorCollection(properties.getVectorDb().getMilvus().getCollectionName());
        metadata.setIndexComplete(false);
        metadataManager.saveMetadata(metadata);

        // 扫描所有Java文件
        List<Path> javaFiles = scanJavaFiles(projectPath);
        
        // 检测是否为多模块项目
        boolean isMultiModule = detectMultiModule(projectPath);

        try {
            int processedFiles = 0;
            int totalChunks = 0;

            if (isMultiModule) {
                ModuleIndexResult result = buildMultiModuleIndex(
                        projectPath, projectInfo, metadata, taskId);
                processedFiles = result.processedFiles();
                totalChunks = result.totalChunks();
            } else {
                SingleModuleIndexResult result = buildSingleModuleIndex(
                        projectPath, projectInfo, metadata, javaFiles, taskId);
                processedFiles = result.processedFiles();
                totalChunks = result.totalChunks();
            }

            metadataManager.updateStats(metadata, processedFiles, totalChunks);
            metadataManager.updateLastCommit(metadata, projectInfo.getCurrentCommitHash());
            metadata.setIndexComplete(true);
            metadataManager.saveMetadata(metadata);
            
            progressTracker.completeTask(taskId);
            log.info("全量索引完成: files={}, chunks={}, taskId={}", processedFiles, totalChunks, taskId);

        } catch (Exception e) {
            progressTracker.failTask(taskId, e.getMessage());
            log.error("全量索引构建失败: projectKey={}, error={}", projectKey, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 构建完整索引
     * 
     * @param projectInfo 项目信息
     * @param indexedBy 索引构建者
     * @param force 是否强制重建
     * @return 索引结果
     */
    public IndexResult buildFullIndex(ProjectInfo projectInfo, String indexedBy, boolean force) {
        String projectKey = projectInfo.getProjectKey();
        Path projectPath = Paths.get(projectInfo.getProjectPath());

        log.info("开始全量索引构建: projectKey={}, force={}", projectKey, force);

        // 如果强制重建,先删除已有索引
        if (force) {
            log.info("强制重建, 删除已有索引: {}", projectKey);
            vectorDatabase.deleteByProjectKey(projectKey);
        }

        // 检查是否已有完整索引
        if (!force && metadataManager.existsIndex(projectKey)) {
            IndexMetadata existingMetadata = metadataManager.loadMetadata(projectKey);
            
            // 只有索引完整构建完成时，才执行增量更新
            if (existingMetadata.getIndexComplete() != null && existingMetadata.getIndexComplete()) {
                log.info("索引已存在且完整，执行增量更新: {}", projectKey);
                IncrementalIndexService.IncrementalResult result =
                        incrementalIndexService.performIncrementalUpdate(projectInfo, indexedBy);
                return IndexResult.fromIncremental(result);
            } else {
                log.warn("索引存在但不完整 (indexComplete={}), 将执行全量重建: {}", 
                        existingMetadata.getIndexComplete(), projectKey);
                // 删除不完整的索引
                vectorDatabase.deleteByProjectKey(projectKey);
            }
        }

        // 创建新元数据
        IndexMetadata metadata = metadataManager.createMetadata(projectInfo, indexedBy);

        // 设置Embedding和VectorDB信息
        metadata.setEmbeddingProvider(properties.getEmbedding().getProvider());
        metadata.setVectorDbType(properties.getVectorDb().getType());
        metadata.setVectorCollection(properties.getVectorDb().getMilvus().getCollectionName());
        
        // 标记索引构建开始，设置为未完成状态
        metadata.setIndexComplete(false);
        metadataManager.saveMetadata(metadata);
        log.info("索引构建开始，标记为未完成状态: projectKey={}", projectKey);

        // 扫描所有Java文件
        List<Path> javaFiles = scanJavaFiles(projectPath);
        int totalFiles = javaFiles.size();

        if (totalFiles == 0) {
            log.warn("未找到Java文件: {}", projectPath);
            return IndexResult.error("未找到Java文件");
        }

        log.info("扫描到 {} 个Java文件", totalFiles);

        // 检测是否为多模块项目
        boolean isMultiModule = detectMultiModule(projectPath);
        log.info("项目类型: {}", isMultiModule ? "多模块项目" : "单模块项目");

        // 创建进度跟踪任务
        String taskId = progressTracker.createTask(projectKey, totalFiles);

        try {
            int processedFiles = 0;
            int totalChunks = 0;

            if (isMultiModule) {
                // 多模块项目：按模块逐个建立索引
                ModuleIndexResult result = buildMultiModuleIndex(
                        projectPath, projectInfo, metadata, taskId);
                processedFiles = result.processedFiles();
                totalChunks = result.totalChunks();
            } else {
                // 单模块项目：按文件逐个建立索引
                SingleModuleIndexResult result = buildSingleModuleIndex(
                        projectPath, projectInfo, metadata, javaFiles, taskId);
                processedFiles = result.processedFiles();
                totalChunks = result.totalChunks();
            }

            // 保存最终元数据
            metadataManager.updateStats(metadata, processedFiles, totalChunks);
            metadataManager.updateLastCommit(metadata, projectInfo.getCurrentCommitHash());
            
            // 标记索引构建完成
            metadata.setIndexComplete(true);
            metadataManager.saveMetadata(metadata);
            log.info("索引构建完成，标记为已完成状态: projectKey={}, files={}, chunks={}", 
                    projectKey, processedFiles, totalChunks);

            progressTracker.completeTask(taskId);

            log.info("全量索引完成: files={}, chunks={}, taskId={}",
                    processedFiles, totalChunks, taskId);

            return IndexResult.success(processedFiles, totalChunks, taskId);

        } catch (Exception e) {
            progressTracker.failTask(taskId, e.getMessage());
            log.error("全量索引构建失败，索引状态保持为未完成: projectKey={}, error={}", 
                    projectKey, e.getMessage(), e);
            
            // 保持indexComplete=false，确保下次重新全量构建
            // 不保存metadata，避免保存部分数据
            return IndexResult.error("索引构建失败: " + e.getMessage());
        }
    }

    /**
     * 单模块项目索引结果
     */
    private record SingleModuleIndexResult(int processedFiles, int totalChunks) {}

    /**
     * 多模块项目索引结果
     */
    private record ModuleIndexResult(int processedFiles, int totalChunks) {}

    /**
     * 构建单模块项目索引
     */
    private SingleModuleIndexResult buildSingleModuleIndex(Path projectPath, ProjectInfo projectInfo,
                                                            IndexMetadata metadata,
                                                            List<Path> javaFiles,
                                                            String taskId) throws Exception {
        int processedFiles = 0;
        int totalChunks = 0;
        String projectKey = projectInfo.getProjectKey();
        int totalFiles = javaFiles.size();

        for (Path javaFile : javaFiles) {
            // 处理单个文件
            int chunksCount = processJavaFile(javaFile, projectPath, projectInfo, metadata, taskId);
            
            if (chunksCount > 0) {
                processedFiles++;
                totalChunks += chunksCount;

                // 定期保存元数据
                if (processedFiles % 100 == 0) {
                    metadataManager.updateStats(metadata, processedFiles, totalChunks);
                    metadataManager.saveMetadata(metadata);
                    log.info("索引进度: {}/{}", processedFiles, totalFiles);
                }
            } else {
                processedFiles++;
            }
        }

        return new SingleModuleIndexResult(processedFiles, totalChunks);
    }

    /**
     * 构建多模块项目索引 - 按模块逐个建立
     */
    private ModuleIndexResult buildMultiModuleIndex(Path projectPath, ProjectInfo projectInfo,
                                                     IndexMetadata metadata,
                                                     String taskId) throws Exception {
        int totalProcessedFiles = 0;
        int totalChunks = 0;
        String projectKey = projectInfo.getProjectKey();

        // 扫描所有模块
        List<ModuleInfo> modules = scanModules(projectPath);
        log.info("检测到 {} 个模块: {}", modules.size(), 
                modules.stream().map(ModuleInfo::name).collect(java.util.stream.Collectors.joining(", ")));

        // 加载已有模块状态
        Map<String, IndexMetadata.ModuleIndexInfo> existingModules = metadata.getModules();

        for (ModuleInfo module : modules) {
            String moduleName = module.name();
            Path modulePath = module.path();

            // 检查模块是否已完成
            IndexMetadata.ModuleIndexInfo moduleInfo = existingModules.get(moduleName);
            if (moduleInfo != null && moduleInfo.getCompleted() != null && moduleInfo.getCompleted()) {
                log.info("⏭️  模块 [{}] 已完成，跳过", moduleName);
                continue;
            }

            log.info("\n{}", "=".repeat(60));
            log.info("📦 开始索引模块: {}", moduleName);
            log.info("📂 模块路径: {}", modulePath);
            log.info("{}", "=".repeat(60));

            // 扫描模块中的Java文件
            List<Path> moduleJavaFiles = scanModuleJavaFiles(modulePath);
            int moduleFileCount = moduleJavaFiles.size();

            if (moduleFileCount == 0) {
                log.warn("模块 [{}] 未找到Java文件，跳过", moduleName);
                // 标记模块为已完成（无文件）
                markModuleComplete(metadata, moduleName, modulePath.toString(), 0, 0);
                continue;
            }

            log.info("模块 [{}] 包含 {} 个Java文件", moduleName, moduleFileCount);

            // 创建模块级别的进度跟踪
            progressTracker.updateModuleProgress(taskId, moduleName, moduleFileCount);

            int moduleProcessedFiles = 0;
            int moduleChunks = 0;

            // 处理模块中的每个文件
            for (Path javaFile : moduleJavaFiles) {
                int chunksCount = processJavaFile(javaFile, projectPath, projectInfo, metadata, taskId);
                
                if (chunksCount > 0) {
                    moduleProcessedFiles++;
                    moduleChunks += chunksCount;
                }

                moduleProcessedFiles++;
                totalProcessedFiles++;

                // 更新模块进度
                progressTracker.updateModuleFileProgress(taskId, moduleName, moduleProcessedFiles);

                // 定期保存元数据和模块状态
                if (moduleProcessedFiles % 50 == 0) {
                    metadataManager.updateStats(metadata, totalProcessedFiles, totalChunks);
                    metadataManager.saveMetadata(metadata);
                    log.info("模块 [{}] 进度: {}/{}", moduleName, moduleProcessedFiles, moduleFileCount);
                }
            }

            // 模块索引完成
            markModuleComplete(metadata, moduleName, modulePath.toString(), 
                    moduleProcessedFiles, moduleChunks);
            totalChunks += moduleChunks;

            log.info("✅ 模块 [{}] 索引完成: {} 个文件, {} 个代码块", 
                    moduleName, moduleProcessedFiles, moduleChunks);

            // 保存模块状态
            metadataManager.saveMetadata(metadata);
        }

        log.info("\n{}", "=".repeat(60));
        log.info("🎉 所有模块索引完成！");
        log.info("📊 总计: {} 个文件, {} 个代码块", totalProcessedFiles, totalChunks);
        log.info("{}", "=".repeat(60));

        return new ModuleIndexResult(totalProcessedFiles, totalChunks);
    }

    /**
     * 处理单个Java文件
     */
    private int processJavaFile(Path javaFile, Path projectPath, ProjectInfo projectInfo,
                                IndexMetadata metadata, String taskId) throws Exception {
        String projectKey = projectInfo.getProjectKey();

        // 1. AST解析
        JavaCodeParser.ParseResult parseResult = javaCodeParser.parseFile(javaFile, projectKey,
                projectInfo.getProjectName());
        List<CodeChunk> chunks = parseResult.getChunks();

        if (chunks.isEmpty()) {
            return 0;
        }

        int processedFiles = getProcessedFilesCount(metadata) + 1;

        // 更新AST解析阶段进度
        if (taskId != null) {
            progressTracker.updateStageProgress(taskId, "AST_PARSING", processedFiles);
        }

        // 2. 分析方法调用关系
        try {
            List<MethodCallRelation> callRelations = methodCallAnalyzer.analyzeCalls(
                    chunks, parseResult.getCompilationUnit());
            
            // 设置调用关系到CodeChunk
            for (MethodCallRelation relation : callRelations) {
                CodeChunk caller = chunks.stream()
                        .filter(c -> c.getId().equals(relation.getCallerId()))
                        .findFirst().orElse(null);
                CodeChunk callee = chunks.stream()
                        .filter(c -> c.getId().equals(relation.getCalleeId()))
                        .findFirst().orElse(null);
                
                if (caller != null && callee != null) {
                    // 添加到calls列表
                    if (caller.getCalls() == null) {
                        caller.setCalls(new ArrayList<>());
                    }
                    caller.getCalls().add(callee.getFullyQualifiedName());
                    
                    // 添加到calledBy列表
                    if (callee.getCalledBy() == null) {
                        callee.setCalledBy(new ArrayList<>());
                    }
                    callee.getCalledBy().add(caller.getFullyQualifiedName());
                    
                    // 设置层级
                    caller.setLayer(detectLayer(caller));
                    callee.setLayer(detectLayer(callee));
                    
                    // 写入Neo4j
                    neo4jGraphService.insertCallEdge(
                            relation.getCallerId(),
                            relation.getCalleeId(),
                            relation.getCallType(),
                            0
                    );
                }
            }
            
            // 为所有方法写入Neo4j节点
            for (CodeChunk chunk : chunks) {
                if (chunk.getChunkType() == CodeChunk.ChunkType.METHOD) {
                    chunk.setLayer(detectLayer(chunk));
                    chunk.setBusinessFlow(detectBusinessFlow(chunk));
                    
                    neo4jGraphService.insertMethodNode(
                            chunk.getId(),
                            chunk.getName(),
                            chunk.getFullyQualifiedName(),
                            chunk.getPackageName() + "." + getClassName(chunk.getFilePath()),
                            chunk.getPackageName(),
                            chunk.getLayer(),
                            chunk.getReturnType(),
                            chunk.getParameters(),
                            chunk.getAnnotations(),
                            chunk.getDescription(),
                            chunk.getBusinessFlow()
                    );
                }
            }
        } catch (Exception e) {
            log.warn("分析方法调用关系失败: {}", javaFile, e);
        }

        // 3. 检测Java Bean类并过滤
        boolean isBeanClass = javaBeanDetector.isJavaBean(chunks);
        if (isBeanClass) {
            chunks = javaBeanDetector.filterChunksForBean(chunks, true);
            log.debug("检测到Java Bean类: {}, 过滤后代码块数: {}", 
                    javaFile.getFileName(), chunks.size());
        }

        // 4. 构建语义文本（自动跳过getter/setter）
        for (CodeChunk chunk : chunks) {
            chunk.setSemanticText(semanticTextBuilder.buildSemanticText(chunk));
        }

        // 过滤掉语义文本为空的代码块（getter/setter）
        chunks = chunks.stream()
                .filter(c -> c.getSemanticText() != null && !c.getSemanticText().isEmpty())
                .collect(java.util.stream.Collectors.toList());

        if (chunks.isEmpty()) {
            return 0;
        }

        // 5. 向量化并插入数据库
        log.info("准备插入 {} 个代码块到向量数据库", chunks.size());
        for (int i = 0; i < Math.min(3, chunks.size()); i++) {
            CodeChunk chunk = chunks.get(i);
            log.info("CodeChunk[{}]: name={}, fqn={}, type={}, lines={}-{}", 
                    i, chunk.getName(), chunk.getFullyQualifiedName(), 
                    chunk.getChunkType(), chunk.getStartLine(), chunk.getEndLine());
        }
        
        List<String> ids = vectorDatabase.insertCodeChunks(chunks);
        log.info("成功插入 {} 个代码块，返回 {} 个ID", chunks.size(), ids.size());

        // 6. 更新元数据
        String relativePath = projectPath.relativize(javaFile).toString();
        metadataManager.updateFileInfo(metadata, relativePath,
                projectInfo.getCurrentCommitHash(), chunks.size(), ids);
        
        // 先累加统计值到metadata
        metadata.setTotalFiles(processedFiles);
        metadata.setTotalChunks(metadata.getTotalChunks() + chunks.size());

        // 7. 更新进度（基于最新的metadata计算累计值）
        if (taskId != null) {
            progressTracker.updateStageProgress(taskId, "EMBEDDING", processedFiles);
            progressTracker.updateStageProgress(taskId, "VECTOR_INSERT", processedFiles);
            
            // 更新代码块统计 - 使用metadata中的累计值
            progressTracker.updateChunkStats(taskId, 
                    metadata.getTotalChunks(), 
                    metadata.getTotalChunks());
        }

        return chunks.size();
    }

    /**
     * 扫描多模块项目的所有模块
     */
    private List<ModuleInfo> scanModules(Path projectPath) throws IOException {
        List<ModuleInfo> modules = new ArrayList<>();

        // 读取pom.xml获取模块列表
        Path pomPath = projectPath.resolve("pom.xml");
        if (Files.exists(pomPath)) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(pomPath.toFile());

                NodeList moduleNodes = doc.getElementsByTagName("module");
                for (int i = 0; i < moduleNodes.getLength(); i++) {
                    String moduleName = moduleNodes.item(i).getTextContent().trim();
                    Path modulePath = projectPath.resolve(moduleName);
                    if (Files.exists(modulePath)) {
                        modules.add(new ModuleInfo(moduleName, modulePath));
                    }
                }
            } catch (Exception e) {
                log.warn("读取pom.xml模块列表失败", e);
            }
        }

        // 如果pom.xml中没有找到模块，则扫描包含src/main/java的子目录
        if (modules.isEmpty()) {
            try (Stream<Path> stream = Files.list(projectPath)) {
                modules = stream
                        .filter(Files::isDirectory)
                        .filter(dir -> Files.exists(dir.resolve("src/main/java")))
                        .map(dir -> new ModuleInfo(dir.getFileName().toString(), dir))
                        .collect(Collectors.toList());
            }
        }

        return modules;
    }

    /**
     * 扫描模块中的Java文件
     */
    private List<Path> scanModuleJavaFiles(Path modulePath) throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        Path srcMainJava = modulePath.resolve("src/main/java");

        if (!Files.exists(srcMainJava)) {
            return javaFiles;
        }

        List<String> excludePatterns = properties.getProject().getExcludePatterns();

        Files.walkFileTree(srcMainJava, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    String filePath = file.toString();
                    boolean excluded = excludePatterns.stream()
                            .anyMatch(pattern -> filePath.contains(pattern));
                    if (!excluded) {
                        javaFiles.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        return javaFiles;
    }

    /**
     * 标记模块索引完成
     */
    private void markModuleComplete(IndexMetadata metadata, String moduleName, 
                                    String modulePath, int fileCount, int chunkCount) {
        IndexMetadata.ModuleIndexInfo moduleInfo = IndexMetadata.ModuleIndexInfo.builder()
                .moduleName(moduleName)
                .modulePath(modulePath)
                .completed(true)
                .fileCount(fileCount)
                .chunkCount(chunkCount)
                .completedAt(java.time.LocalDateTime.now())
                .build();

        metadata.getModules().put(moduleName, moduleInfo);
        log.info("📝 模块 [{}] 状态已保存: {} 个文件, {} 个代码块", 
                moduleName, fileCount, chunkCount);
    }

    /**
     * 模块信息
     */
    private record ModuleInfo(String name, Path path) {}

    /**
     * 检测方法的架构层级
     */
    private String detectLayer(CodeChunk chunk) {
        String annotations = chunk.getAnnotations();
        String fqn = chunk.getFullyQualifiedName();
        
        if (annotations != null) {
            if (annotations.contains("RestController") || annotations.contains("Controller")) {
                return "CONTROLLER";
            }
            if (annotations.contains("Service")) {
                return "SERVICE";
            }
            if (annotations.contains("Repository") || annotations.contains("Mapper")) {
                return "DAO";
            }
        }
        
        if (fqn != null) {
            if (fqn.contains(".controller.")) return "CONTROLLER";
            if (fqn.contains(".service.")) return "SERVICE";
            if (fqn.contains(".dao.") || fqn.contains(".mapper.") || fqn.contains(".repository.")) {
                return "DAO";
            }
        }
        
        return "OTHER";
    }

    /**
     * 检测业务流程
     */
    private String detectBusinessFlow(CodeChunk chunk) {
        String fqn = chunk.getFullyQualifiedName();
        if (fqn == null) return null;
        
        // 根据包名或类名推断业务流程
        if (fqn.contains("order") || fqn.contains("Order")) return "ORDER_MANAGEMENT";
        if (fqn.contains("payment") || fqn.contains("Payment")) return "PAYMENT_PROCESS";
        if (fqn.contains("user") || fqn.contains("User")) return "USER_MANAGEMENT";
        if (fqn.contains("product") || fqn.contains("Product")) return "PRODUCT_MANAGEMENT";
        
        return null;
    }

    /**
     * 从文件路径提取类名
     */
    private String getClassName(String filePath) {
        if (filePath == null) return "";
        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
        return fileName.replace(".java", "");
    }

    /**
     * 扫描项目中的所有Java文件
     * 支持单模块和多模块项目
     */
    private List<Path> scanJavaFiles(Path projectPath) {
        List<Path> javaFiles = new ArrayList<>();

        // 获取排除模式
        List<String> excludePatterns = properties.getProject().getExcludePatterns();

        // 检查是否为多模块项目 (存在子模块)
        boolean isMultiModule = detectMultiModule(projectPath);

        if (isMultiModule) {
            // 多模块项目: 递归扫描所有子模块的src/main/java
            log.info("检测到多模块项目，递归扫描所有子模块");
            scanMultiModuleJavaFiles(projectPath, javaFiles, excludePatterns);
        } else {
            // 单模块项目: 扫描配置的目录
            scanSingleModuleJavaFiles(projectPath, javaFiles, excludePatterns);
        }

        log.info("扫描完成: 找到 {} 个Java文件", javaFiles.size());
        return javaFiles;
    }

    /**
     * 检测是否为多模块项目
     */
    private boolean detectMultiModule(Path projectPath) {
        // 检查是否有pom.xml并包含<modules>
        Path pomFile = projectPath.resolve("pom.xml");
        if (Files.exists(pomFile)) {
            try {
                String content = Files.readString(pomFile);
                return content.contains("<modules>") && content.contains("</modules>");
            } catch (IOException e) {
                log.warn("读取pom.xml失败", e);
            }
        }

        // 检查是否有settings.gradle并包含include
        Path gradleFile = projectPath.resolve("settings.gradle");
        if (Files.exists(gradleFile)) {
            return true; // settings.gradle存在通常意味着多模块
        }
        Path gradleKtsFile = projectPath.resolve("settings.gradle.kts");
        if (Files.exists(gradleKtsFile)) {
            return true;
        }

        // 检查是否存在多个包含src/main/java的子目录
        try {
            long moduleCount = Files.list(projectPath)
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve("src/main/java")))
                    .count();
            return moduleCount > 1;
        } catch (IOException e) {
            log.warn("检查子模块失败", e);
        }

        return false;
    }

    /**
     * 扫描多模块项目的所有Java文件
     */
    private void scanMultiModuleJavaFiles(Path projectPath, List<Path> javaFiles, 
                                          List<String> excludePatterns) {
        try {
            Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 跳过隐藏目录和排除目录
                    String dirName = dir.getFileName().toString();
                    if (dirName.startsWith(".") || dirName.equals("target") || 
                        dirName.equals("build") || dirName.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        String filePath = file.toString();
                        
                        // 检查是否应该排除
                        boolean excluded = excludePatterns.stream()
                                .anyMatch(pattern -> matchesPattern(filePath, pattern));

                        if (!excluded) {
                            javaFiles.add(file);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("访问文件失败: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("扫描多模块项目失败", e);
        }
    }

    /**
     * 扫描单模块项目的Java文件
     */
    private void scanSingleModuleJavaFiles(Path projectPath, List<Path> javaFiles,
                                           List<String> excludePatterns) {
        List<String> scanPaths = properties.getProject().getScanPaths();
        if (scanPaths.isEmpty()) {
            scanPaths = List.of("src/main/java");
        }

        for (String scanPath : scanPaths) {
            Path scanDir = projectPath.resolve(scanPath);
            if (!Files.exists(scanDir)) {
                continue;
            }

            try {
                Files.walkFileTree(scanDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String filePath = file.toString();

                        // 检查是否应该排除
                        boolean excluded = excludePatterns.stream()
                                .anyMatch(pattern -> matchesPattern(filePath, pattern));

                        if (!excluded && filePath.endsWith(".java")) {
                            javaFiles.add(file);
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        log.warn("访问文件失败: {}", file, exc);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.error("扫描目录失败: {}", scanDir, e);
            }
        }
    }

    /**
     * 检查文件路径是否匹配glob模式
     */
    private boolean matchesPattern(String filePath, String pattern) {
        // 简化实现: 仅支持**和*通配符
        String regex = pattern
                .replace("**/", "(.*/)?")
                .replace("**", ".*")
                .replace("*", "[^/]*");
        return filePath.matches(regex);
    }

    /**
     * 索引结果
     */
    @Data
    public static class IndexResult {
        private boolean success;
        private String message;
        private String taskId;
        private int indexedFiles;
        private int indexedChunks;

        public static IndexResult success(int files, int chunks, String taskId) {
            IndexResult result = new IndexResult();
            result.success = true;
            result.message = String.format("索引完成: %d个文件, %d个代码块", files, chunks);
            result.taskId = taskId;
            result.indexedFiles = files;
            result.indexedChunks = chunks;
            return result;
        }

        public static IndexResult fromIncremental(IncrementalIndexService.IncrementalResult result) {
            IndexResult indexResult = new IndexResult();
            indexResult.success = result.isSuccess();
            indexResult.message = result.getMessage();
            indexResult.indexedFiles = result.getAddedFiles() + result.getModifiedFiles();
            return indexResult;
        }

        public static IndexResult error(String message) {
            IndexResult result = new IndexResult();
            result.success = false;
            result.message = message;
            return result;
        }
    }

    /**
     * 获取已处理文件数
     */
    private int getProcessedFilesCount(IndexMetadata metadata) {
        return metadata.getTotalFiles();
    }

    /**
     * 获取总代码块数
     */
    private int getTotalChunksCount(IndexMetadata metadata) {
        return metadata.getTotalChunks();
    }
}
