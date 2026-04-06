package com.javacontext.index.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacontext.index.config.AppProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 豆包多模态向量模型适配器（符合官方API规范）
 * 
 * 支持豆包官方多模态Embedding模型:
 * - doubao-embedding-vision: 多模态模型（250615版本），支持文本、图片、视频混合输入
 * - text-embedding-large-3: 4096维文本模型
 * - text-embedding-medium-3: 1024维文本模型  
 * - text-embedding-small-3: 512维文本模型
 * 
 * API规范:
 * - 端点: https://ark.cn-beijing.volces.com/api/v3/embeddings
 * - 方法: POST
 * - 认证: Bearer Token
 * - 内容类型: application/json
 * 
 * 请求体格式:
 * {
 *   "model": "doubao-embedding-vision",
 *   "input": [
 *     {"type": "text", "text": "这是一段文本"},
 *     {"type": "image_url", "image_url": {"url": "https://example.com/image.jpg"}}
 *   ],
 *   "encoding_format": "float"
 * }
 * 
 * @see <a href="https://www.volcengine.com/docs/82379/1523520">豆包向量化API文档</a>
 */
@Slf4j
public class DoubaoEmbeddingModel implements EmbeddingModel {

    // 文本向量化API端点（多模态端点也支持纯文本）
    private static final String TEXT_API_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal";
    // 多模态向量化API端点
    private static final String MULTIMODAL_API_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final AppProperties.DoubaoConfig config;
    private final HttpClient httpClient;
    private final int maxRetries;
    private final int batchSize;
    private final String textApiEndpoint;      // 文本API端点
    private final String multimodalApiEndpoint; // 多模态API端点
    
    // 统计信息
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger totalTokens = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);

    public DoubaoEmbeddingModel(AppProperties.DoubaoConfig config, int maxRetries, int batchSize) {
        this.config = config;
        this.maxRetries = maxRetries;
        this.batchSize = batchSize;
        
        // 根据配置选择API端点
        String customEndpoint = config.getApiEndpoint();
        if (customEndpoint != null && !customEndpoint.isEmpty()) {
            // 如果自定义了端点，文本和多模态都用同一个
            this.textApiEndpoint = customEndpoint;
            this.multimodalApiEndpoint = customEndpoint;
        } else {
            // 使用默认端点
            this.textApiEndpoint = TEXT_API_ENDPOINT;
            this.multimodalApiEndpoint = MULTIMODAL_API_ENDPOINT;
        }
        
        // 配置HTTP客户端（HTTP/2 + 连接池）
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(config.getTimeout()))
                .build();
        
        log.info("初始化豆包Embedding模型: model={}, dimension={}, textEndpoint={}, multimodalEndpoint={}, timeout={}s, maxRetries={}", 
                config.getModelName(), getDimension(), textApiEndpoint, multimodalApiEndpoint, config.getTimeout(), maxRetries);
    }

    /**
     * 单个文本向量化（兼容接口）
     */
    @Override
    public Response<Embedding> embed(String text) {
        return embed(TextSegment.from(text));
    }

    /**
     * TextSegment向量化（LangChain4j接口）
     */
    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        totalRequests.incrementAndGet();
        
        if (textSegment == null || textSegment.text() == null || textSegment.text().trim().isEmpty()) {
            return Response.from(
                    Embedding.from(new float[getDimension()]),
                    new TokenUsage(0, 0));
        }
        
        try {
            // 豆包多模态API期望input是对象数组，即使是纯文本
            // 格式: [{"type":"text","text":"..."}]
            String text = textSegment.text().trim();
            String jsonPayload = buildTextRequest(text);
            String response = executeWithRetry(jsonPayload);
            
            Embedding embedding = parseEmbeddingResponse(response);
            int tokenCount = countTokens(text);
            totalTokens.addAndGet(tokenCount);
            
            return Response.from(embedding, new TokenUsage(tokenCount, 0));
        } catch (Exception e) {
            failedRequests.incrementAndGet();
            log.error("豆包Embedding向量化失败: model={}, error={}", 
                    config.getModelName(), e.getMessage(), e);
            throw new RuntimeException("豆包Embedding向量化失败", e);
        }
    }

    /**
     * 批量文本向量化（LangChain4j接口）
     */
    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        if (textSegments == null || textSegments.isEmpty()) {
            return Response.from(new ArrayList<>(), new TokenUsage(0, 0));
        }

        List<Embedding> embeddings = new ArrayList<>();
        int totalTokenCount = 0;

        try {
            for (TextSegment segment : textSegments) {
                Response<Embedding> response = embed(segment);
                embeddings.add(response.content());
                totalTokenCount += response.tokenUsage().totalTokenCount();
            }
            
            return Response.from(embeddings, new TokenUsage(totalTokenCount, 0));
        } catch (Exception e) {
            log.error("批量Embedding失败: count={}, error={}", textSegments.size(), e.getMessage(), e);
            throw new RuntimeException("批量Embedding失败", e);
        }
    }

    /**
     * 多模态向量化（支持文本、图片、视频混合输入）
     * 
     * @param textSegments 文本列表
     * @param imageUrls 图片URL列表（可选）
     * @param imageBase64s Base64编码图片列表（可选）
     * @return 向量列表
     */
    public List<float[]> embedMultimodal(
            List<String> textSegments, 
            List<String> imageUrls,
            List<String> imageBase64s) {
        
        if (textSegments == null || textSegments.isEmpty()) {
            return List.of();
        }

        List<float[]> results = new ArrayList<>();
        
        try {
            // 构建多模态输入
            List<InputContent> inputList = new ArrayList<>();
            
            // 添加文本内容
            if (textSegments != null) {
                for (String text : textSegments) {
                    if (text != null && !text.trim().isEmpty()) {
                        inputList.add(new InputContent("text", text.trim(), null));
                    }
                }
            }
            
            // 添加图片URL
            if (imageUrls != null) {
                for (String url : imageUrls) {
                    if (url != null && !url.isEmpty()) {
                        inputList.add(new InputContent("image_url", null, url));
                    }
                }
            }
            
            // 添加Base64图片
            if (imageBase64s != null) {
                for (String base64 : imageBase64s) {
                    if (base64 != null && !base64.isEmpty()) {
                        String dataUri = "data:image/jpeg;base64," + base64;
                        inputList.add(new InputContent("image_url", null, dataUri));
                    }
                }
            }
            
            if (inputList.isEmpty()) {
                return results;
            }
            
            String jsonPayload = buildMultimodalRequest(inputList);
            // 多模态请求使用多模态端点
            String response = executeMultimodalRequest(jsonPayload);
            
            // 解析多模态响应
            List<float[]> vectors = parseMultimodalResponse(response);
            results.addAll(vectors);
            
            log.info("多模态向量化成功: texts={}, images={}, vectors={}", 
                    textSegments.size(), 
                    (imageUrls != null ? imageUrls.size() : 0) + (imageBase64s != null ? imageBase64s.size() : 0),
                    results.size());
            
        } catch (Exception e) {
            log.error("多模态Embedding失败: error={}", e.getMessage(), e);
            throw new RuntimeException("多模态Embedding失败", e);
        }
        
        return results;
    }

    /**
     * 构建文本请求JSON（豆包多模态API期望input是对象数组）
     * 格式: {"model":"xxx","input":[{"type":"text","text":"..."}],"encoding_format":"float"}
     */
    private String buildTextRequest(String text) throws Exception {
        // 使用Jackson构建JSON，确保格式正确
        var rootNode = JSON_MAPPER.createObjectNode();
        rootNode.put("model", config.getModelName());
        
        var inputArray = JSON_MAPPER.createArrayNode();
        
        // 纯文本也要用对象格式
        var textNode = JSON_MAPPER.createObjectNode();
        textNode.put("type", "text");
        textNode.put("text", text);
        inputArray.add(textNode);
        
        rootNode.set("input", inputArray);
        rootNode.put("encoding_format", "float");
        
        return JSON_MAPPER.writeValueAsString(rootNode);
    }

    /**
     * 构建多模态请求JSON（用于embedMultimodal方法）
     */
    private String buildMultimodalRequest(List<InputContent> inputList) throws Exception {
        var rootNode = JSON_MAPPER.createObjectNode();
        rootNode.put("model", config.getModelName());
        
        var inputArray = JSON_MAPPER.createArrayNode();
        for (InputContent content : inputList) {
            var contentNode = JSON_MAPPER.createObjectNode();
            contentNode.put("type", content.type());
            
            if ("text".equals(content.type())) {
                contentNode.put("text", content.text());
            } else if ("image_url".equals(content.type())) {
                var imageUrlNode = JSON_MAPPER.createObjectNode();
                imageUrlNode.put("url", content.imageUrl());
                contentNode.set("image_url", imageUrlNode);
            }
            
            inputArray.add(contentNode);
        }
        rootNode.set("input", inputArray);
        rootNode.put("encoding_format", "float");
        
        return JSON_MAPPER.writeValueAsString(rootNode);
    }

    /**
     * 执行HTTP请求（带重试）
     */
    private String executeWithRetry(String jsonPayload) throws Exception {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return executeRequest(jsonPayload);
            } catch (Exception e) {
                lastException = e;
                log.warn("豆包API调用失败 (尝试 {}/{}): {}", attempt, maxRetries, e.getMessage());
                
                if (attempt < maxRetries) {
                    // 指数退避: 1s, 2s, 4s
                    long delay = (long) Math.pow(2, attempt - 1) * 1000;
                    Thread.sleep(delay);
                }
            }
        }
        
        throw lastException;
    }

    /**
     * 执行单次HTTP请求（使用文本API端点）
     */
    private String executeRequest(String jsonPayload) throws Exception {
        log.debug("豆包API请求 - 端点: {}, 请求体: {}", textApiEndpoint, jsonPayload);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(textApiEndpoint))  // 使用文本端点
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(config.getTimeout()))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, 
                HttpResponse.BodyHandlers.ofString()
        );

        log.debug("豆包API响应 - 状态码: {}, 响应体: {}", response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException(String.format(
                    "豆包API返回错误: status=%d, body=%s",
                    response.statusCode(), response.body()));
        }

        return response.body();
    }

    /**
     * 执行多模态HTTP请求（使用多模态API端点）
     */
    private String executeMultimodalRequest(String jsonPayload) throws Exception {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(multimodalApiEndpoint))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.getApiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .timeout(Duration.ofSeconds(config.getTimeout()))
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, 
                        HttpResponse.BodyHandlers.ofString()
                );

                if (response.statusCode() != 200) {
                    throw new RuntimeException(String.format(
                            "豆包多模态API返回错误: status=%d, body=%s",
                            response.statusCode(), response.body()));
                }

                return response.body();
            } catch (Exception e) {
                lastException = e;
                log.warn("豆包多模态API调用失败 (尝试 {}/{}): {}", attempt, maxRetries, e.getMessage());
                
                if (attempt < maxRetries) {
                    long delay = (long) Math.pow(2, attempt - 1) * 1000;
                    Thread.sleep(delay);
                }
            }
        }
        
        throw lastException;
    }

    /**
     * 解析单模态响应
     * 
     * 响应格式:
     * {
     *   "data": {
     *     "embedding": [-0.123, -0.355, ...],
     *     "object": "embedding"
     *   },
     *   "model": "doubao-embedding-vision-250615",
     *   "usage": {...}
     * }
     */
    private Embedding parseEmbeddingResponse(String jsonResponse) throws Exception {
        log.debug("豆包API响应: {}", jsonResponse);
        
        JsonNode root = JSON_MAPPER.readTree(jsonResponse);
        JsonNode data = root.get("data");
        
        if (data == null) {
            log.error("豆包API返回数据格式错误 - 缺少data字段，完整响应: {}", jsonResponse);
            throw new RuntimeException("豆包API返回数据格式错误: 缺少data字段");
        }
        
        // data 是对象，不是数组！
        JsonNode embeddingNode = data.get("embedding");
        if (embeddingNode == null || !embeddingNode.isArray()) {
            log.error("响应中缺少embedding数组字段: {}", jsonResponse);
            throw new RuntimeException("响应中缺少embedding字段");
        }
        
        float[] vector = new float[embeddingNode.size()];
        
        for (int i = 0; i < embeddingNode.size(); i++) {
            vector[i] = (float) embeddingNode.get(i).asDouble();
        }
        
        log.debug("成功解析向量: 维度={}", vector.length);
        return Embedding.from(vector);
    }

    /**
     * 解析多模态响应
     * 
     * 注意：即使是多模态输入，响应也是单个embedding对象
     * 格式: {"data": {"embedding": [...]}, ...}
     */
    private List<float[]> parseMultimodalResponse(String jsonResponse) throws Exception {
        JsonNode root = JSON_MAPPER.readTree(jsonResponse);
        JsonNode data = root.get("data");
        
        if (data == null) {
            throw new RuntimeException("豆包API返回数据格式错误: 缺少data字段");
        }
        
        // data 是对象，不是数组
        JsonNode embeddingNode = data.get("embedding");
        if (embeddingNode == null || !embeddingNode.isArray()) {
            throw new RuntimeException("响应中缺少embedding字段");
        }
        
        float[] vector = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            vector[i] = (float) embeddingNode.get(i).asDouble();
        }
        
        // 返回单个向量（多模态融合后的结果）
        List<float[]> vectors = new ArrayList<>();
        vectors.add(vector);
        return vectors;
    }

    /**
     * 估算token数量
     */
    private int countTokens(String text) {
        int chineseChars = 0;
        int otherChars = 0;
        
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        
        return chineseChars + (otherChars / 4);
    }

    /**
     * 获取向量维度
     * 优先使用配置中的维度，如果配置未指定则根据模型名称推断
     */
    private int getDimension() {
        // 优先使用AppProperties中配置的维度
        // 注意：这里需要从外部传入维度配置，暂时使用模型名称推断作为后备
        
        String model = config.getModelName().toLowerCase();
        
        // 根据模型名称推断维度
        if (model.contains("large") || model.contains("vision")) {
            // 大多数large/vision模型是4096维，但有些自定义模型可能是2048维
            // 建议在使用自定义模型时明确配置dimension
            return 4096;
        } else if (model.contains("medium")) {
            return 1024;
        } else if (model.contains("small")) {
            return 512;
        } else if (model.startsWith("ep-")) {
            // 自定义部署模型，返回2048维（根据实际测试结果）
            return 2048;
        } else {
            // 默认返回2048维（更安全的选择）
            log.warn("未识别的模型: {}, 使用默认维度2048。建议在配置中明确指定dimension", config.getModelName());
            return 2048;
        }
    }

    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format(
                "DoubaoEmbedding Stats: total=%d, failed=%d, tokens=%d, failRate=%.2f%%",
                totalRequests.get(),
                failedRequests.get(),
                totalTokens.get(),
                totalRequests.get() > 0 ? (failedRequests.get() * 100.0 / totalRequests.get()) : 0
        );
    }

    /**
     * 输入内容（多模态）
     */
    private record InputContent(String type, String text, String imageUrl) {}
}
