package com.javacontext.index.embedding;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 向量缓存服务
 * 
 * 缓存已生成的向量,避免重复调用Embedding API
 * 相同内容复用向量,降低成本和提高性能
 * 
 * 使用Caffeine实现本地LRU缓存
 */
@Slf4j
@Component
public class VectorCacheService {

    private final Cache<String, float[]> vectorCache;
    private final MessageDigest md5Digest;
    
    // 统计信息
    private long hitCount = 0;
    private long missCount = 0;

    public VectorCacheService() {
        // 初始化MD5
        try {
            this.md5Digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5算法不可用", e);
        }

        // 配置Caffeine缓存
        // 最大10000个条目, 2小时过期
        this.vectorCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(2, TimeUnit.HOURS)
                .recordStats()
                .build();

        log.info("向量缓存服务初始化完成: 最大容量=10000, 过期时间=2小时");
    }

    /**
     * 获取向量 (带缓存)
     * 
     * @param text 文本内容
     * @param vectorComputer 向量计算函数 (String -> float[])
     * @return 向量
     */
    public float[] getVector(String text, Function<String, float[]> vectorComputer) {
        // 生成缓存key
        String cacheKey = generateCacheKey(text);

        // 尝试从缓存获取
        float[] cached = vectorCache.getIfPresent(cacheKey);
        if (cached != null) {
            hitCount++;
            log.debug("向量缓存命中: key={}, 命中率={:.2f}%", 
                    cacheKey.substring(0, 8), getHitRate());
            return cached;
        }

        // 缓存未命中,调用计算函数
        missCount++;
        float[] vector = vectorComputer.apply(text);
        
        // 存入缓存
        vectorCache.put(cacheKey, vector);
        
        log.debug("向量缓存未命中,已缓存: key={}, 命中率={:.2f}%", 
                cacheKey.substring(0, 8), getHitRate());
        
        return vector;
    }

    /**
     * 批量获取向量 (带缓存)
     * 
     * @param texts 文本列表
     * @param vectorComputer 向量计算函数 (String -> float[])
     * @return 向量列表
     */
    public List<float[]> getVectors(List<String> texts, Function<String, float[]> vectorComputer) {
        return texts.stream()
                .map(text -> getVector(text, vectorComputer))
                .toList();
    }

    /**
     * 生成缓存key (使用MD5)
     */
    private String generateCacheKey(String text) {
        byte[] hash = md5Digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 获取缓存命中率
     */
    public double getHitRate() {
        long total = hitCount + missCount;
        if (total == 0) {
            return 0.0;
        }
        return (double) hitCount / total * 100;
    }

    /**
     * 获取缓存大小
     */
    public long getCacheSize() {
        return vectorCache.estimatedSize();
    }

    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format("缓存大小: %d, 命中: %d, 未命中: %d, 命中率: %.2f%%",
                vectorCache.estimatedSize(), hitCount, missCount, getHitRate());
    }

    /**
     * 清空缓存
     */
    public void clear() {
        vectorCache.invalidateAll();
        hitCount = 0;
        missCount = 0;
        log.info("向量缓存已清空");
    }

    /**
     * 移除指定key的缓存
     */
    public void invalidate(String text) {
        String cacheKey = generateCacheKey(text);
        vectorCache.invalidate(cacheKey);
    }
}
