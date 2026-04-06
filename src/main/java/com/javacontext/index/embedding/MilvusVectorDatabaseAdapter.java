package com.javacontext.index.embedding;

import com.javacontext.index.config.AppProperties;
import com.javacontext.index.model.CodeChunk;
import com.javacontext.index.model.SearchResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.grpc.DataType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Milvus向量数据库适配器
 * 
 * 实现VectorDatabaseStrategy接口,提供Milvus-specific的实现
 * 
 * 统一接口:
 * - initialize(): 初始化连接和集合
 * - insertCodeChunks(): 批量插入代码块
 * - search(): 向量相似度搜索
 * - deleteByFilePath(): 根据文件路径删除
 * - deleteByProjectKey(): 根据项目Key删除
 */
@Slf4j
public class MilvusVectorDatabaseAdapter implements VectorDatabaseStrategy {

    private final AppProperties properties;
    private final EmbeddingService embeddingService;
    private MilvusServiceClient milvusClient;
    private String collectionName;

    // 字段定义
    private static final String FIELD_ID = "id";
    private static final String FIELD_PROJECT_KEY = "project_key";
    private static final String FIELD_PROJECT_NAME = "project_name";
    private static final String FIELD_FILE_PATH = "file_path";
    private static final String FIELD_CHUNK_TYPE = "chunk_type";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_FQN = "fully_qualified_name";
    private static final String FIELD_CODE = "code";
    private static final String FIELD_SEMANTIC_TEXT = "semantic_text";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_PACKAGE_NAME = "package_name";
    private static final String FIELD_MODIFIER = "modifier";
    private static final String FIELD_PARAMETERS = "parameters";
    private static final String FIELD_RETURN_TYPE = "return_type";
    private static final String FIELD_ANNOTATIONS = "annotations";
    private static final String FIELD_START_LINE = "start_line";
    private static final String FIELD_END_LINE = "end_line";
    private static final String FIELD_VECTOR = "vector";
        
    // Milvus VarChar字段最大长度（字节）
    // 注意：Milvus的VarChar长度是字节数，不是字符数
    // 中文字符在UTF-8中占用3-4字节，所以需要预留余量
    private static final int MAX_CODE_LENGTH = 60000;  // 留5KB余量
    private static final int MAX_TEXT_LENGTH = 60000;  // 留5KB余量

    public MilvusVectorDatabaseAdapter(AppProperties properties, EmbeddingService embeddingService) {
        this.properties = properties;
        this.embeddingService = embeddingService;
    }

    @Override
    public void initialize() {
        AppProperties.MilvusConfig config = properties.getVectorDb().getMilvus();
        this.collectionName = config.getCollectionName();

        log.info("初始化Milvus向量数据库: {}:{}", config.getHost(), config.getPort());

        // 创建连接
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(config.getHost())
                .withPort(config.getPort())
                .build();

        milvusClient = new MilvusServiceClient(connectParam);

        // 启动时调试：列出所有集合
        log.info("=== 启动时集合检查 ===");
        List<String> existingCollections = listCollections();
        log.info("当前Milvus中的集合: {}", existingCollections);
        log.info("目标集合 {} 是否存在: {}", collectionName, existingCollections.contains(collectionName));
        log.info("========================");

        // 清理旧的集合
        //cleanupOldCollections();

        // 创建集合 (如果不存在)
        createCollectionIfNotExists();

        // 创建索引 (如果不存在)
        createIndexIfNotExists();

        log.info("Milvus向量数据库初始化完成, 集合: {}", collectionName);
    }

    /**
     * 清理旧的集合
     */
    private void cleanupOldCollections() {
        String[] oldCollections = {"java_code_index", "java_code_index_v2"};
        
        for (String oldCollection : oldCollections) {
            if (hasCollection(oldCollection)) {
                try {
                    milvusClient.dropCollection(DropCollectionParam.newBuilder()
                            .withCollectionName(oldCollection)
                            .build());
                    log.info("删除旧集合: {}", oldCollection);
                } catch (Exception e) {
                    log.warn("删除旧集合失败: {}", oldCollection, e);
                }
            }
        }
    }

    /**
     * 获取所有集合列表
     */
    public List<String> listCollections() {
        try {
            var response = milvusClient.showCollections(
                    io.milvus.param.collection.ShowCollectionsParam.newBuilder().build());
            
            if (response.getData() != null) {
                return response.getData().getCollectionNamesList();
            }
        } catch (Exception e) {
            log.error("获取集合列表失败", e);
        }
        return Collections.emptyList();
    }

    /**
     * 从指定集合中提取项目列表
     * 格式：project_name字段，去重返回
     */
    public List<String> getProjectsFromCollection(String collectionName) {
        try {
            // 查询所有不同的project_name
            var response = milvusClient.query(
                    io.milvus.param.dml.QueryParam.newBuilder()
                            .withCollectionName(collectionName)
                            .withExpr("")
                            .withOutFields(List.of(FIELD_PROJECT_NAME))
                            .withLimit(10000L)  // 最多返回10000条
                            .build());

            if (response.getData() != null) {
                // 提取project_name并去重
                Set<String> projects = new LinkedHashSet<>();
                QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
                
                for (long i = 0L; i < wrapper.getRowCount(); i++) {
                    String projectName = (String) wrapper.getFieldWrapper(FIELD_PROJECT_NAME).getFieldData().get((int)i).toString();
                    if (projectName != null && !projectName.isEmpty()) {
                        projects.add(projectName);
                    }
                }
                
                return new ArrayList<>(projects);
            }
        } catch (Exception e) {
            log.error("从集合 {} 获取项目列表失败", collectionName, e);
        }
        return Collections.emptyList();
    }

    @Override
    public void close() {
        if (milvusClient != null) {
            milvusClient.close();
            log.info("Milvus连接已关闭");
        }
    }

    /**
     * 创建集合 (如果不存在)
     */
    private void createCollectionIfNotExists() {
        boolean exists = hasCollection(collectionName);
        log.info("检查集合 {} 是否存在: {}", collectionName, exists);
        
        if (!exists) {
            log.info("开始创建 Milvus 集合：{}", collectionName);
    
            int dimension = embeddingService.getDimension();
    
            // 构建字段列表
            List<FieldType> fieldTypes = new ArrayList<>();
    
            // ID 字段 (主键)
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_ID)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(100)
                    .withPrimaryKey(true)
                    .withAutoID(false)
                    .build());
    
            // project_key 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_PROJECT_KEY)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(500)
                    .build());
    
            // project_name 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_PROJECT_NAME)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(200)
                    .build());
    
            // file_path 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_FILE_PATH)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(500)
                    .build());
    
            // chunk_type 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_CHUNK_TYPE)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(50)
                    .build());
    
            // name 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_NAME)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(200)
                    .build());
    
            // fully_qualified_name 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_FQN)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(500)
                    .build());
    
            // code 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_CODE)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
                    .build());
    
            // semantic_text 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_SEMANTIC_TEXT)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
                    .build());
    
            // description 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_DESCRIPTION)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
                    .build());
    
            // package_name 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_PACKAGE_NAME)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(300)
                    .build());
    
            // modifier 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_MODIFIER)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(100)
                    .build());
    
            // parameters 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_PARAMETERS)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(2000)
                    .build());
    
            // return_type 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_RETURN_TYPE)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(500)
                    .build());
    
            // annotations 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_ANNOTATIONS)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(1000)
                    .build());
    
            // start_line 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_START_LINE)
                    .withDataType(DataType.Int32)
                    .build());
    
            // end_line 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_END_LINE)
                    .withDataType(DataType.Int32)
                    .build());
    
            // vector 字段
            fieldTypes.add(FieldType.newBuilder()
                    .withName(FIELD_VECTOR)
                    .withDataType(DataType.FloatVector)
                    .withDimension(dimension)
                    .build());
    
            // 创建集合
            CreateCollectionParam param = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldTypes(fieldTypes)
                    .withShardsNum(2)
                    .build();
    
            milvusClient.createCollection(param);
            log.info("创建 Milvus 集合成功：{}", collectionName);
            
            // 验证集合是否真的创建成功
            boolean verifyExists = hasCollection(collectionName);
            log.info("验证集合 {} 创建结果: {}", collectionName, verifyExists ? "成功" : "失败");
        } else {
            log.info("Milvus 集合已存在：{}", collectionName);
        }
    }

    /**
     * 创建向量索引 (如果不存在)
     */
    private void createIndexIfNotExists() {
        AppProperties.MilvusConfig config = properties.getVectorDb().getMilvus();

        IndexType indexType = IndexType.valueOf(config.getIndexType());
        MetricType metricType = MetricType.valueOf(config.getMetricType());

        // 创建向量索引
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(FIELD_VECTOR)
                .withIndexType(indexType)
                .withMetricType(metricType)
                .withExtraParam("{\"nlist\":1024}")
                .withSyncMode(Boolean.TRUE)
                .build();

        milvusClient.createIndex(indexParam);
        log.info("创建向量索引：type={}, metric={}", indexType, metricType);
    
        // 加载集合到内存，使索引生效
        milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());
        log.info("Milvus 集合已加载到内存，索引生效");
    }

    /**
     * 检查集合是否存在
     */
    private boolean hasCollection(String collectionName) {
        var response = milvusClient.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
        return response.getData();
    }

    /**
     * 批量插入代码块
     *
     * @param chunk 代码块列表
     * @return 插入成功的代码块ID列表
     */
    @Override
    public String insertCodeChunk(CodeChunk chunk) {
        List<String> ids = insertCodeChunks(List.of(chunk));
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Override
    public List<String> insertCodeChunks(List<CodeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        log.info("开始插入 {} 个代码块到向量数据库", chunks.size());
        
        // 打印第一个CodeChunk的详细信息用于调试
        if (!chunks.isEmpty()) {
            CodeChunk firstChunk = chunks.get(0);
            log.info("=== 第一个CodeChunk调试信息 ===");
            log.info("ID: {}", firstChunk.getId());
            log.info("projectKey: {}", firstChunk.getProjectKey());
            log.info("projectName: {}", firstChunk.getProjectName());
            log.info("filePath: {}", firstChunk.getFilePath());
            log.info("chunkType: {}", firstChunk.getChunkType());
            log.info("name: {}", firstChunk.getName());
            log.info("fullyQualifiedName: {}", firstChunk.getFullyQualifiedName());
            log.info("packageName: {}", firstChunk.getPackageName());
            log.info("startLine: {}", firstChunk.getStartLine());
            log.info("endLine: {}", firstChunk.getEndLine());
            log.info("================================");
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<String> projectKeys = new ArrayList<>();
        List<String> projectNames = new ArrayList<>();
        List<String> filePaths = new ArrayList<>();
        List<String> chunkTypes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> fqns = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        List<String> semanticTexts = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        List<String> packageNames = new ArrayList<>();
        List<String> modifiers = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        List<String> returnTypes = new ArrayList<>();
        List<String> annotations = new ArrayList<>();
        List<Integer> startLines = new ArrayList<>();
        List<Integer> endLines = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();

        for (CodeChunk chunk : chunks) {
            ids.add(chunk.getId());
            projectKeys.add(chunk.getProjectKey() != null ? chunk.getProjectKey() : "");
            projectNames.add(chunk.getProjectName() != null ? chunk.getProjectName() : "");
            filePaths.add(chunk.getFilePath() != null ? chunk.getFilePath() : "");
            chunkTypes.add(chunk.getChunkType() != null ? chunk.getChunkType().name() : "");
            names.add(chunk.getName() != null ? chunk.getName() : "");
            fqns.add(chunk.getFullyQualifiedName() != null ? chunk.getFullyQualifiedName() : "");
            
            // 截断超长字段，避免Milvus VarChar长度限制
            // 注意：Milvus VarChar的长度是字节数，中文字符UTF-8编码占3-4字节
            String code = chunk.getCode() != null ? chunk.getCode() : "";
            if (code.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CODE_LENGTH) {
                log.warn("代码块 {} 的code字段超长 ({} 字符)，截断至 {} 字符", 
                        chunk.getId(), code.length(), MAX_CODE_LENGTH / 4);
                // 按字符截断，确保不超过字节限制
                code = truncateByBytes(code, MAX_CODE_LENGTH);
            }
            codes.add(code);
            
            String semanticText = chunk.getSemanticText() != null ? chunk.getSemanticText() : "";
            if (semanticText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_LENGTH) {
                log.warn("代码块 {} 的semanticText字段超长 ({} 字符)，截断", 
                        chunk.getId(), semanticText.length());
                semanticText = truncateByBytes(semanticText, MAX_TEXT_LENGTH);
            }
            semanticTexts.add(semanticText);
            
            String description = chunk.getDescription() != null ? chunk.getDescription() : "";
            if (description.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_LENGTH) {
                log.warn("代码块 {} 的description字段超长 ({} 字符)，截断", 
                        chunk.getId(), description.length());
                description = truncateByBytes(description, MAX_TEXT_LENGTH);
            }
            descriptions.add(description);
            
            // 检查其他可能超长的字段
            String fqn = chunk.getFullyQualifiedName() != null ? chunk.getFullyQualifiedName() : "";
            if (fqn.length() > 500) {
                log.warn("代码块 {} 的fqn字段超长 ({} 字符)，截断至 500 字符", 
                        chunk.getId(), fqn.length());
                fqn = fqn.substring(0, 500);
            }
            // 注意：fqns已经在前面添加了，需要更新
            fqns.set(fqns.size() - 1, fqn);
            
            packageNames.add(chunk.getPackageName() != null ? chunk.getPackageName() : "");
            modifiers.add(chunk.getModifier() != null ? chunk.getModifier() : "");
            parameters.add(chunk.getParameters() != null ? chunk.getParameters() : "");
            returnTypes.add(chunk.getReturnType() != null ? chunk.getReturnType() : "");
            annotations.add(chunk.getAnnotations() != null ? chunk.getAnnotations() : "");
            startLines.add(chunk.getStartLine());
            endLines.add(chunk.getEndLine());

            // 向量化
            float[] vector = embeddingService.embed(chunk.getSemanticText());
            List<Float> floatList = new ArrayList<>();
            for (float v : vector) {
                floatList.add(v);
            }
            vectors.add(floatList);
        }

        fields.add(new InsertParam.Field(FIELD_ID, ids));
        fields.add(new InsertParam.Field(FIELD_PROJECT_KEY, projectKeys));
        fields.add(new InsertParam.Field(FIELD_PROJECT_NAME, projectNames));
        fields.add(new InsertParam.Field(FIELD_FILE_PATH, filePaths));
        fields.add(new InsertParam.Field(FIELD_CHUNK_TYPE, chunkTypes));
        fields.add(new InsertParam.Field(FIELD_NAME, names));
        fields.add(new InsertParam.Field(FIELD_FQN, fqns));
        fields.add(new InsertParam.Field(FIELD_CODE, codes));
        fields.add(new InsertParam.Field(FIELD_SEMANTIC_TEXT, semanticTexts));
        fields.add(new InsertParam.Field(FIELD_DESCRIPTION, descriptions));
        fields.add(new InsertParam.Field(FIELD_PACKAGE_NAME, packageNames));
        fields.add(new InsertParam.Field(FIELD_MODIFIER, modifiers));
        fields.add(new InsertParam.Field(FIELD_PARAMETERS, parameters));
        fields.add(new InsertParam.Field(FIELD_RETURN_TYPE, returnTypes));
        fields.add(new InsertParam.Field(FIELD_ANNOTATIONS, annotations));
        fields.add(new InsertParam.Field(FIELD_START_LINE, startLines));
        fields.add(new InsertParam.Field(FIELD_END_LINE, endLines));
        fields.add(new InsertParam.Field(FIELD_VECTOR, vectors));
        
        // 调试：打印插入数据的样本
        if (!names.isEmpty()) {
            log.info("=== 插入数据调试信息 ===");
            log.info("准备插入 {} 条记录", ids.size());
            log.info("示例 name[0]: {}", names.get(0));
            log.info("示例 fqn[0]: {}", fqns.get(0));
            log.info("示例 packageName[0]: {}", packageNames.get(0));
            log.info("示例 projectKey[0]: {}", projectKeys.get(0));
            log.info("==========================");
        }

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();

        var response = milvusClient.insert(insertParam);
        if (response.getException() != null) {
            throw new RuntimeException("插入向量数据库失败：" + response.getException().getMessage());
        }

        log.info("成功插入 {} 个代码块", chunks.size());
        return ids;
    }

    /**
     * 向量相似度搜索
     *
     * @param queryVector 查询向量
     * @param projectKey  项目Key (过滤)
     * @param topK        返回结果数
     * @return 搜索结果列表
     */
    @Override
    public List<SearchResult> search(float[] queryVector, String projectKey, int topK) {
        // 转换向量为 List<Float>
        List<Float> vectorList = new ArrayList<>();
        for (float v : queryVector) {
            vectorList.add(v);
        }
    
        SearchParam.Builder builder = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withMetricType(MetricType.COSINE)
                .withOutFields(Arrays.asList(
                        FIELD_ID, FIELD_PROJECT_KEY, FIELD_PROJECT_NAME, FIELD_FILE_PATH,
                        FIELD_CHUNK_TYPE, FIELD_NAME, FIELD_FQN, FIELD_CODE,
                        FIELD_SEMANTIC_TEXT, FIELD_DESCRIPTION, FIELD_PACKAGE_NAME,
                        FIELD_MODIFIER, FIELD_PARAMETERS, FIELD_RETURN_TYPE, FIELD_ANNOTATIONS,
                        FIELD_START_LINE, FIELD_END_LINE))
                .withVectorFieldName(FIELD_VECTOR)
                .withVectors(List.of(vectorList))
                .withTopK(topK)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG);
    
        // 如果有 projectKey，添加过滤表达式
        if (projectKey != null && !projectKey.isEmpty()) {
            builder.withExpr(FIELD_PROJECT_KEY + " == \"" + projectKey + "\"");
        }
    
        SearchParam searchParam = builder.build();
    
        var response = milvusClient.search(searchParam);
        if (response.getException() != null) {
            throw new RuntimeException("搜索失败：" + response.getException().getMessage());
        }
        
        return parseSearchResults(response.getData());
    }

    /**
     * 解析搜索结果
     * 直接使用Milvus原生gRPC数据结构，避免SearchResultsWrapper的字段聚合问题
     */
    private List<SearchResult> parseSearchResults(io.milvus.grpc.SearchResults response) {
        List<SearchResult> results = new ArrayList<>();
        
        // 使用原生gRPC数据结构
        io.milvus.grpc.SearchResultData resultData = response.getResults();
        
        // 获取IDs
        io.milvus.grpc.IDs ids = resultData.getIds();
        List<String> strIds = ids.getStrId().getDataList();
        
        // 获取分数
        List<Float> scores = resultData.getScoresList();
        
        // 构建字段数据Map
        Map<String, io.milvus.grpc.FieldData> fieldDataMap = new HashMap<>();
        for (io.milvus.grpc.FieldData fieldData : resultData.getFieldsDataList()) {
            fieldDataMap.put(fieldData.getFieldName(), fieldData);
        }
        
        log.info("=== 搜索结果调试信息（原生解析） ===");
        log.info("总结果数: {}", strIds.size());
        
        for (int i = 0; i < strIds.size(); i++) {
            SearchResult result = SearchResult.builder()
                    .chunkId(strIds.get(i))
                    .score(scores.get(i))
                    .projectKey(getStringFromFieldData(fieldDataMap.get(FIELD_PROJECT_KEY), i))
                    .projectName(getStringFromFieldData(fieldDataMap.get(FIELD_PROJECT_NAME), i))
                    .filePath(getStringFromFieldData(fieldDataMap.get(FIELD_FILE_PATH), i))
                    .chunkType(CodeChunk.ChunkType.safeValueOf(
                            getStringFromFieldData(fieldDataMap.get(FIELD_CHUNK_TYPE), i)))
                    .name(getStringFromFieldData(fieldDataMap.get(FIELD_NAME), i))
                    .fullyQualifiedName(getStringFromFieldData(fieldDataMap.get(FIELD_FQN), i))
                    .code(getStringFromFieldData(fieldDataMap.get(FIELD_CODE), i))
                    .semanticText(getStringFromFieldData(fieldDataMap.get(FIELD_SEMANTIC_TEXT), i))
                    .description(getStringFromFieldData(fieldDataMap.get(FIELD_DESCRIPTION), i))
                    .packageName(getStringFromFieldData(fieldDataMap.get(FIELD_PACKAGE_NAME), i))
                    .modifier(getStringFromFieldData(fieldDataMap.get(FIELD_MODIFIER), i))
                    .parameters(getStringFromFieldData(fieldDataMap.get(FIELD_PARAMETERS), i))
                    .returnType(getStringFromFieldData(fieldDataMap.get(FIELD_RETURN_TYPE), i))
                    .annotations(getStringFromFieldData(fieldDataMap.get(FIELD_ANNOTATIONS), i))
                    .startLine(getIntFromFieldData(fieldDataMap.get(FIELD_START_LINE), i))
                    .endLine(getIntFromFieldData(fieldDataMap.get(FIELD_END_LINE), i))
                    .build();
            results.add(result);
        }
        
        // 打印第一个结果用于调试
        if (!results.isEmpty()) {
            SearchResult firstResult = results.get(0);
            log.info("第一个结果 - ID: {}, name: {}, fqn: {}, packageName: {}", 
                    firstResult.getChunkId(), firstResult.getName(), 
                    firstResult.getFullyQualifiedName(), firstResult.getPackageName());
            log.info("第一个结果 - projectKey: {}, projectKey类型: {}", 
                    firstResult.getProjectKey(), firstResult.getProjectKey().getClass().getSimpleName());
        }
        log.info("==========================================");

        return results;
    }

    /**
     * 从FieldData中提取字符串值
     */
    private String getStringFromFieldData(io.milvus.grpc.FieldData fieldData, int index) {
        if (fieldData == null) {
            return "";
        }
        
        try {
            io.milvus.grpc.ScalarField scalarField = fieldData.getScalars();
            if (scalarField.hasStringData()) {
                List<String> dataList = scalarField.getStringData().getDataList();
                if (index >= 0 && index < dataList.size()) {
                    return dataList.get(index);
                }
            }
        } catch (Exception e) {
            log.warn("提取字符串字段失败: index={}", index, e);
        }
        return "";
    }

    /**
     * 从FieldData中提取整数值
     */
    private int getIntFromFieldData(io.milvus.grpc.FieldData fieldData, int index) {
        if (fieldData == null) {
            return 0;
        }
        
        try {
            io.milvus.grpc.ScalarField scalarField = fieldData.getScalars();
            if (scalarField.hasIntData()) {
                List<Integer> dataList = scalarField.getIntData().getDataList();
                if (index >= 0 && index < dataList.size()) {
                    return dataList.get(index);
                }
            }
        } catch (Exception e) {
            log.warn("提取整数字段失败: index={}", index, e);
        }
        return 0;
    }

    /**
     * 根据文件路径删除代码块
     *
     * @param projectKey 项目Key
     * @param filePath   文件路径
     * @return 删除数量
     */
    @Override
    public int deleteByFilePath(String projectKey, String filePath) {
        String expr = FIELD_PROJECT_KEY + " == \"" + projectKey + "\" && " +
                FIELD_FILE_PATH + " == \"" + filePath + "\"";

        var response = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build());
        
        if (response.getException() != null) {
            log.error("删除文件索引失败：{}", response.getException().getMessage());
            return 0;
        } else {
            log.info("删除文件索引：{}", filePath);
            return (int) response.getData().getDeleteCnt();
        }
    }

    /**
     * 根据项目Key删除所有代码块
     *
     * @param projectKey 项目Key
     * @return 删除数量
     */
    @Override
    public int deleteByProjectKey(String projectKey) {
        String expr = FIELD_PROJECT_KEY + " == \"" + projectKey + "\"";

        var response = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build());
        
        if (response.getException() != null) {
            log.error("删除项目索引失败：{}", response.getException().getMessage());
            return 0;
        } else {
            log.info("删除项目索引：{}", projectKey);
            return (int) response.getData().getDeleteCnt();
        }
    }

    /**
     * 根据项目名称删除所有代码块
     * @param projectName 项目名称
     * @return 删除数量
     */
    public int deleteByProjectName(String projectName) {
        // 注意：这里使用project_name字段，但实际数据中可能存储的是projectKey
        // 为了兼容，同时检查两个字段
        String expr = FIELD_PROJECT_NAME + " == \"" + projectName + "\" || " +
                      FIELD_PROJECT_KEY + " == \"" + projectName + "\"";

        var response = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build());
        
        if (response.getException() != null) {
            log.error("删除项目索引失败：{}", response.getException().getMessage());
            return 0;
        } else {
            long deleteCnt = response.getData().getDeleteCnt();
            log.info("删除项目索引：{}, 删除记录数: {}", projectName, deleteCnt);
            return (int) deleteCnt;
        }
    }

    /**
     * 删除整个Milvus集合
     * @param collectionName 集合名称
     * @return 是否成功
     */
    public boolean dropCollection(String collectionName) {
        try {
            log.info("删除Milvus集合: {}", collectionName);
            R<RpcStatus> response = milvusClient.dropCollection(
                    DropCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            
            if (response.getException() != null) {
                log.error("删除集合失败: {}", response.getException().getMessage());
                return false;
            }
            
            log.info("集合删除成功: {}", collectionName);
            return true;
        } catch (Exception e) {
            log.error("删除集合异常", e);
            return false;
        }
    }

    /**
     * 根据代码块ID列表删除
     * @param chunkIds 代码块ID列表
     * @return 删除数量
     */
    @Override
    public int deleteByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }

        String idsStr = chunkIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(", "));
        String expr = FIELD_ID + " in [" + idsStr + "]";

        var response = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build());
        
        if (response.getException() != null) {
            log.error("删除代码块失败：{}", response.getException().getMessage());
            return 0;
        } else {
            log.info("删除 {} 个代码块", chunkIds.size());
            return (int) response.getData().getDeleteCnt();
        }
    }

    @Override
    public String getDbType() {
        return "milvus";
    }

    @Override
    public boolean updateChunk(CodeChunk chunk) {
        // Milvus不支持直接更新,需要先删除再插入
        try {
            deleteByChunkIds(List.of(chunk.getId()));
            insertCodeChunk(chunk);
            return true;
        } catch (Exception e) {
            log.error("更新代码块失败: {}", chunk.getId(), e);
            return false;
        }
    }

    @Override
    public boolean existsIndex(String projectKey) {
        try {
            String expr = FIELD_PROJECT_KEY + " == \"" + projectKey + "\"";
            var response = milvusClient.query(
                    io.milvus.param.dml.QueryParam.newBuilder()
                            .withCollectionName(collectionName)
                            .withExpr(expr)
                            .withLimit(1L)
                            .build()
            );
            return response.getException() == null
                    && response.getData() != null;
        } catch (Exception e) {
            log.error("检查索引存在失败: {}", projectKey, e);
            return false;
        }
    }

    @Override
    public Map<String, Object> getStats(String projectKey) {
        Map<String, Object> stats = new HashMap<>();
        try {
            String expr = FIELD_PROJECT_KEY + " == \"" + projectKey + "\"";
            var response = milvusClient.query(
                    io.milvus.param.dml.QueryParam.newBuilder()
                            .withCollectionName(collectionName)
                            .withExpr(expr)
                            .withOutFields(List.of(FIELD_ID))
                            .build()
            );
            
            if (response.getException() == null && response.getData() != null) {
                QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
                int chunkCount = wrapper.getRowRecords() != null ? wrapper.getRowRecords().size() : 0;
                stats.put("chunkCount", chunkCount);
                stats.put("projectKey", projectKey);
            }
        } catch (Exception e) {
            log.error("获取统计信息失败: {}", projectKey, e);
        }
        return stats;
    }
    
    /**
     * 按字节长度截断字符串
     * 确保截断后的字符串UTF-8编码不超过maxBytes字节
     */
    private String truncateByBytes(String str, int maxBytes) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return str;
        }
        
        // 找到合适的截断点，避免截断多字节字符
        int truncateIndex = maxBytes;
        while (truncateIndex > 0) {
            // 检查是否是UTF-8多字节字符的起始位置
            // UTF-8编码规则：
            // 0xxxxxxx - ASCII (1字节)
            // 110xxxxx - 2字节起始 (110 + 10)
            // 1110xxxx - 3字节起始 (1110 + 10 + 10)
            // 11110xxx - 4字节起始 (11110 + 10 + 10 + 10)
            // 10xxxxxx -  continuation byte
            byte b = bytes[truncateIndex];
            if ((b & 0xC0) != 0x80) {  // 不是 continuation byte
                break;
            }
            truncateIndex--;
        }
        
        // 将字节数组转换回字符串
        return new String(bytes, 0, truncateIndex, java.nio.charset.StandardCharsets.UTF_8);
    }
}
