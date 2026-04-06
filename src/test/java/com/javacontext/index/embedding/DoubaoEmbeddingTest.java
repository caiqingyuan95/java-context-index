package com.javacontext.index.embedding;

import com.javacontext.index.config.AppProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * 豆包Embedding API测试用例
 * 
 * 测试文本向量化API调用
 */
@Slf4j
public class DoubaoEmbeddingTest {

    public static void main(String[] args) {
        log.info("========== 豆包Embedding API测试 ==========");
        
        try {
            // 1. 创建配置
            AppProperties.DoubaoConfig config = new AppProperties.DoubaoConfig();
            config.setApiKey("c39f536a-e724-458f-8d49-2aff72d54597"); // 从环境变量读取API Key
            // 使用多模态模型，根据官方文档
            config.setModelName("doubao-embedding-vision-250615");
            config.setTimeout(60);
            
            // 检查API Key是否配置
            if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
                log.error("❌ 错误：未设置DOUBAO_API_KEY环境变量");
                log.info("请设置环境变量: export DOUBAO_API_KEY=your-api-key");
                System.exit(1);
            }
            
            log.info("✅ 配置信息:");
            log.info("   - Model: {}", config.getModelName());
            log.info("   - Timeout: {}s", config.getTimeout());
            log.info("   - API Endpoint: 使用默认端点");
            
            // 2. 创建Embedding模型
            DoubaoEmbeddingModel model = new DoubaoEmbeddingModel(config, 3, 10);
            
            // 3. 测试单个文本向量化
            log.info("\n========== 测试1: 单个文本向量化 ==========");
            String testText1 = "Java是一种面向对象的编程语言，具有跨平台特性";
            log.info("输入文本: {}", testText1);
            
            float[] vector1 = model.embed(testText1).content().vector();
            log.info("✅ 向量化成功!");
            log.info("   - 向量维度: {}", vector1.length);
            log.info("   - 向量前5个值: [{}, {}, {}, {}, {}]", 
                    vector1[0], vector1[1], vector1[2], vector1[3], vector1[4]);
            
            // 4. 测试英文文本
            log.info("\n========== 测试2: 英文文本向量化 ==========");
            String testText2 = "Machine learning is a subset of artificial intelligence";
            log.info("输入文本: {}", testText2);
            
            float[] vector2 = model.embed(testText2).content().vector();
            log.info("✅ 向量化成功!");
            log.info("   - 向量维度: {}", vector2.length);
            
            // 5. 测试长文本
            log.info("\n========== 测试3: 长文本向量化 ==========");
            String testText3 = "Spring Boot是一个用于简化新Spring应用的初始搭建以及开发过程的框架。" +
                    "它使用特定的方式进行配置，使开发人员不再需要定义样板化的配置。" +
                    "Spring Boot致力于在蓬勃发展的快速应用开发领域成为领导者。";
            log.info("输入文本长度: {} 字符", testText3.length());
            
            float[] vector3 = model.embed(testText3).content().vector();
            log.info("✅ 向量化成功!");
            log.info("   - 向量维度: {}", vector3.length);
            
            // 6. 计算文本相似度（余弦相似度）
            log.info("\n========== 测试4: 文本相似度计算 ==========");
            float similarity = calculateCosineSimilarity(vector1, vector3);
            log.info("中文文本 vs 长中文文本 相似度: {:.4f}", similarity);
            
            float similarityEn = calculateCosineSimilarity(vector2, vector2);
            log.info("英文文本 vs 自身 相似度: {:.4f} (应该接近1.0)", similarityEn);
            
            // 7. 打印统计信息
            log.info("\n========== 统计信息 ==========");
            log.info("{}", model.getStats());
            
            log.info("\n========== 所有测试通过! ✅ ==========");
            
        } catch (Exception e) {
            log.error("❌ 测试失败: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * 计算两个向量的余弦相似度
     */
    private static float calculateCosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1.length != vec2.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        
        return (float) (dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2)));
    }
}
