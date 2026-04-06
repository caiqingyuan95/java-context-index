package com.javacontext.index.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacontext.index.config.AppProperties;
import com.javacontext.index.model.CodeChunk;
import com.javacontext.index.model.SearchResult;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ChromaDB向量数据库适配器
 * 
 * 轻量级向量数据库,适合个人和小团队使用
 * 通过REST API与ChromaDB交互
 */
@Slf4j
public class ChromaDBVectorDatabaseAdapter implements VectorDatabaseStrategy {

    private final AppProperties properties;
    private final EmbeddingService embeddingService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    private String baseUrl;
    private String collectionName;

    // 元数据字段常量
    private static final String META_PROJECT_KEY = "project_key";
    private static final String META_PROJECT_NAME = "project_name";
    private static final String META_FILE_PATH = "file_path";
    private static final String META_CHUNK_TYPE = "chunk_type";
    private static final String META_NAME = "name";
    private static final String META_FQN = "fully_qualified_name";
    private static final String META_CODE = "code";
    private static final String META_SEMANTIC_TEXT = "semantic_text";
    private static final String META_DESCRIPTION = "description";
    private static final String META_PACKAGE_NAME = "package_name";
    private static final String META_START_LINE = "start_line";
    private static final String META_END_LINE = "end_line";

    public ChromaDBVectorDatabaseAdapter(AppProperties properties, EmbeddingService embeddingService) {
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void initialize() {
        AppProperties.ChromadbConfig config = properties.getVectorDb().getChromadb();
        this.baseUrl = config.getBaseUrl();
        this.collectionName = config.getCollectionName();

        log.info("初始化ChromaDB向量数据库: {}", baseUrl);

        try {
            // 创建集合 (如果不存在)
            createCollectionIfNotExists();
            log.info("ChromaDB向量数据库初始化完成, 集合: {}", collectionName);
        } catch (Exception e) {
            log.error("ChromaDB初始化失败", e);
            throw new RuntimeException("ChromaDB初始化失败", e);
        }
    }

    @Override
    public void close() {
        // HttpClient不需要关闭
        log.info("ChromaDB连接已关闭");
    }

    /**
     * 创建集合 (如果不存在)
     */
    private void createCollectionIfNotExists() throws Exception {
        // 检查集合是否存在
        String checkUrl = baseUrl + "/api/v1/collections/" + collectionName;
        HttpRequest checkRequest = HttpRequest.newBuilder()
                .uri(URI.create(checkUrl))
                .GET()
                .build();

        HttpResponse<String> checkResponse = httpClient.send(checkRequest, 
                HttpResponse.BodyHandlers.ofString());

        if (checkResponse.statusCode() == 404) {
            // 集合不存在,创建集合
            log.info("创建ChromaDB集合: {}", collectionName);
            
            int dimension = embeddingService.getDimension();
            String createBody = objectMapper.writeValueAsString(Map.of(
                    "name", collectionName,
                    "metadata", Map.of("dimension", dimension)
            ));

            HttpRequest createRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/collections"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(createBody))
                    .build();

            HttpResponse<String> createResponse = httpClient.send(createRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
                throw new RuntimeException("创建ChromaDB集合失败: " + createResponse.body());
            }
        }
    }

    @Override
    public String insertCodeChunk(CodeChunk chunk) {
        List<CodeChunk> chunks = Collections.singletonList(chunk);
        List<String> ids = insertCodeChunks(chunks);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Override
    public List<String> insertCodeChunks(List<CodeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 构建批量插入请求
            List<String> ids = new ArrayList<>();
            List<List<Double>> embeddings = new ArrayList<>();
            List<Map<String, Object>> metadatas = new ArrayList<>();
            List<String> documents = new ArrayList<>();

            for (CodeChunk chunk : chunks) {
                // 向量化
                float[] vector = embeddingService.embed(chunk.getSemanticText());
                
                ids.add(chunk.getId());
                embeddings.add(convertFloatArrayToList(vector));
                
                // 构建元数据
                Map<String, Object> metadata = buildMetadata(chunk);
                metadatas.add(metadata);
                
                // 文档内容 (用于全文搜索)
                documents.add(chunk.getSemanticText());
            }

            // ChromaDB upsert请求
            String upsertBody = objectMapper.writeValueAsString(Map.of(
                    "ids", ids,
                    "embeddings", embeddings,
                    "metadatas", metadatas,
                    "documents", documents
            ));

            String upsertUrl = baseUrl + "/api/v1/collections/" + collectionName + "/upsert";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(upsertUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(upsertBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("ChromaDB插入失败: {}", response.body());
                throw new RuntimeException("ChromaDB插入失败: " + response.body());
            }

            log.info("成功插入 {} 个代码块到ChromaDB", chunks.size());
            return ids;

        } catch (Exception e) {
            log.error("ChromaDB批量插入失败", e);
            throw new RuntimeException("ChromaDB批量插入失败", e);
        }
    }

    @Override
    public List<SearchResult> search(float[] queryVector, String projectKey, int topK) {
        try {
            // 构建查询
            Map<String, Object> queryBody = new HashMap<>();
            queryBody.put("query_embeddings", Collections.singletonList(convertFloatArrayToList(queryVector)));
            queryBody.put("n_results", topK);
            queryBody.put("include", Arrays.asList("metadatas", "documents", "distances"));

            // 添加过滤条件
            if (projectKey != null && !projectKey.isEmpty()) {
                queryBody.put("where", Map.of(META_PROJECT_KEY, projectKey));
            }

            String queryJson = objectMapper.writeValueAsString(queryBody);
            String queryUrl = baseUrl + "/api/v1/collections/" + collectionName + "/query";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(queryUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(queryJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("ChromaDB搜索失败: {}", response.body());
                return Collections.emptyList();
            }

            // 解析响应
            return parseSearchResults(response.body());

        } catch (Exception e) {
            log.error("ChromaDB搜索异常", e);
            return Collections.emptyList();
        }
    }

    @Override
    public int deleteByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }

        try {
            String deleteBody = objectMapper.writeValueAsString(Map.of(
                    "ids", chunkIds
            ));

            String deleteUrl = baseUrl + "/api/v1/collections/" + collectionName + "/delete";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(deleteUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(deleteBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("ChromaDB删除失败: {}", response.body());
                return 0;
            }

            log.info("ChromaDB删除 {} 个代码块", chunkIds.size());
            return chunkIds.size();

        } catch (Exception e) {
            log.error("ChromaDB删除异常", e);
            return 0;
        }
    }

    @Override
    public int deleteByFilePath(String projectKey, String filePath) {
        try {
            // ChromaDB支持where过滤删除
            String deleteBody = objectMapper.writeValueAsString(Map.of(
                    "where", Map.of(
                            META_PROJECT_KEY, projectKey,
                            META_FILE_PATH, filePath
                    )
            ));

            String deleteUrl = baseUrl + "/api/v1/collections/" + collectionName + "/delete";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(deleteUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(deleteBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("ChromaDB删除文件失败: {}", response.body());
                return 0;
            }

            log.info("ChromaDB删除文件: {}", filePath);
            return 1; // 简化处理

        } catch (Exception e) {
            log.error("ChromaDB删除文件异常", e);
            return 0;
        }
    }

    @Override
    public int deleteByProjectKey(String projectKey) {
        try {
            String deleteBody = objectMapper.writeValueAsString(Map.of(
                    "where", Map.of(META_PROJECT_KEY, projectKey)
            ));

            String deleteUrl = baseUrl + "/api/v1/collections/" + collectionName + "/delete";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(deleteUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(deleteBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("ChromaDB删除项目失败: {}", response.body());
                return 0;
            }

            log.info("ChromaDB删除项目索引: {}", projectKey);
            return 1; // 简化处理

        } catch (Exception e) {
            log.error("ChromaDB删除项目异常", e);
            return 0;
        }
    }

    @Override
    public boolean updateChunk(CodeChunk chunk) {
        // ChromaDB的upsert即为更新或插入
        try {
            insertCodeChunk(chunk);
            return true;
        } catch (Exception e) {
            log.error("ChromaDB更新代码块失败", e);
            return false;
        }
    }

    @Override
    public boolean existsIndex(String projectKey) {
        try {
            // 查询是否存在该项目的数据
            float[] dummyVector = new float[embeddingService.getDimension()];
            List<SearchResult> results = search(dummyVector, projectKey, 1);
            return !results.isEmpty();
        } catch (Exception e) {
            log.error("检查ChromaDB索引存在失败", e);
            return false;
        }
    }

    @Override
    public Map<String, Object> getStats(String projectKey) {
        Map<String, Object> stats = new HashMap<>();
        try {
            // ChromaDB没有直接的count API,需要通过查询估算
            stats.put("dbType", "chromadb");
            stats.put("collection", collectionName);
            stats.put("projectKey", projectKey);
        } catch (Exception e) {
            log.error("获取ChromaDB统计信息失败", e);
        }
        return stats;
    }

    @Override
    public String getDbType() {
        return "chromadb";
    }

    /**
     * 构建元数据Map
     */
    private Map<String, Object> buildMetadata(CodeChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(META_PROJECT_KEY, chunk.getProjectKey());
        metadata.put(META_PROJECT_NAME, chunk.getProjectName());
        metadata.put(META_FILE_PATH, chunk.getFilePath());
        metadata.put(META_CHUNK_TYPE, chunk.getChunkType());
        metadata.put(META_NAME, chunk.getName());
        metadata.put(META_FQN, chunk.getFullyQualifiedName());
        metadata.put(META_CODE, chunk.getCode());
        metadata.put(META_SEMANTIC_TEXT, chunk.getSemanticText());
        metadata.put(META_DESCRIPTION, chunk.getDescription());
        metadata.put(META_PACKAGE_NAME, chunk.getPackageName());
        metadata.put(META_START_LINE, chunk.getStartLine());
        metadata.put(META_END_LINE, chunk.getEndLine());
        return metadata;
    }

    /**
     * 转换float数组为List<Double>
     */
    private List<Double> convertFloatArrayToList(float[] vector) {
        List<Double> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add((double) v);
        }
        return list;
    }

    /**
     * 解析搜索结果
     */
    private List<SearchResult> parseSearchResults(String responseBody) throws Exception {
        List<SearchResult> results = new ArrayList<>();
        JsonNode root = objectMapper.readTree(responseBody);
        
        JsonNode idsArray = root.path("ids").path(0);
        JsonNode distancesArray = root.path("distances").path(0);
        JsonNode metadatasArray = root.path("metadatas").path(0);
        JsonNode documentsArray = root.path("documents").path(0);

        for (int i = 0; i < idsArray.size(); i++) {
            String id = idsArray.get(i).asText();
            double distance = distancesArray.get(i).asDouble();
            double score = 1.0 - distance; // 转换为相似度分数

            JsonNode metadata = metadatasArray.get(i);
            
            SearchResult result = SearchResult.builder()
                    .chunkId(id)
                    .score(score)
                    .projectKey(metadata.path(META_PROJECT_KEY).asText())
                    .projectName(metadata.path(META_PROJECT_NAME).asText())
                    .filePath(metadata.path(META_FILE_PATH).asText())
                    .chunkType(CodeChunk.ChunkType.safeValueOf(metadata.path(META_CHUNK_TYPE).asText()))
                    .name(metadata.path(META_NAME).asText())
                    .fullyQualifiedName(metadata.path(META_FQN).asText())
                    .code(metadata.path(META_CODE).asText())
                    .semanticText(metadata.path(META_SEMANTIC_TEXT).asText())
                    .description(metadata.path(META_DESCRIPTION).asText())
                    .packageName(metadata.path(META_PACKAGE_NAME).asText())
                    .startLine(metadata.path(META_START_LINE).asInt())
                    .endLine(metadata.path(META_END_LINE).asInt())
                    .build();

            results.add(result);
        }

        return results;
    }
}
