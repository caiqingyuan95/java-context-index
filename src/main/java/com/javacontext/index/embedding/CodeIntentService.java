package com.javacontext.index.embedding;

import com.javacontext.index.model.CodeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码意图增强服务
 * 
 * 分析代码并提取"代码意图",增强语义索引的准确性
 * 
 * 代码意图包括:
 * 1. 功能意图 (做什么): CRUD、验证、转换、计算等
 * 2. 技术意图 (怎么做): REST API、数据库操作、消息队列等  
 * 3. 业务意图 (为什么): 用户认证、订单处理、支付等
 * 
 * 通过分析:
 * - 方法名/类名语义
 * - 注解信息 (@RestController, @Service等)
 * - 方法调用模式
 * - 参数和返回值类型
 * - 代码结构特征
 */
@Slf4j
@Service
public class CodeIntentService {

    // 功能意图模式
    private static final Map<Pattern, String> FUNCTIONAL_INTENTS = Map.ofEntries(
            Map.entry(Pattern.compile("(?i)(get|find|search|query|fetch|retrieve)"), "数据查询"),
            Map.entry(Pattern.compile("(?i)(create|insert|add|save|register)"), "数据创建"),
            Map.entry(Pattern.compile("(?i)(update|modify|edit|change|set)"), "数据更新"),
            Map.entry(Pattern.compile("(?i)(delete|remove|drop)"), "数据删除"),
            Map.entry(Pattern.compile("(?i)(valid|check|verify|authenticate|authorize)"), "验证校验"),
            Map.entry(Pattern.compile("(?i)(convert|transform|parse|format)"), "数据转换"),
            Map.entry(Pattern.compile("(?i)(calculat|comput|process)"), "计算处理"),
            Map.entry(Pattern.compile("(?i)(send|notify|publish|emit)"), "消息发送"),
            Map.entry(Pattern.compile("(?i)(handle|manage|control)"), "流程控制")
    );

    // 技术意图模式
    private static final Map<Pattern, String> TECHNICAL_INTENTS = Map.ofEntries(
            Map.entry(Pattern.compile("@RestController|@Controller|@RequestMapping"), "REST API"),
            Map.entry(Pattern.compile("@Service"), "业务服务"),
            Map.entry(Pattern.compile("@Repository|EntityManager|JpaRepository"), "数据访问"),
            Map.entry(Pattern.compile("@Component|@Bean"), "Spring组件"),
            Map.entry(Pattern.compile("@EventListener|ApplicationEventPublisher"), "事件驱动"),
            Map.entry(Pattern.compile("@Async|CompletableFuture"), "异步处理"),
            Map.entry(Pattern.compile("@Scheduled|@EnableScheduling"), "定时任务"),
            Map.entry(Pattern.compile("KafkaTemplate|RabbitTemplate|JmsTemplate"), "消息队列"),
            Map.entry(Pattern.compile("RedisTemplate|Cacheable"), "缓存操作"),
            Map.entry(Pattern.compile("RestTemplate|WebClient|HttpClient"), "HTTP调用"),
            Map.entry(Pattern.compile("@Transactional"), "事务管理")
    );

    // 业务意图关键词
    private static final Map<Pattern, String> BUSINESS_INTENTS = Map.ofEntries(
            Map.entry(Pattern.compile("(?i)(user|account|login|auth)"), "用户认证"),
            Map.entry(Pattern.compile("(?i)(order|cart|checkout|payment)"), "订单支付"),
            Map.entry(Pattern.compile("(?i)(product|inventory|stock)"), "商品库存"),
            Map.entry(Pattern.compile("(?i)(notification|email|sms|push)"), "消息通知"),
            Map.entry(Pattern.compile("(?i)(report|statistics|analytics)"), "报表统计"),
            Map.entry(Pattern.compile("(?i)(permission|role|access)"), "权限管理"),
            Map.entry(Pattern.compile("(?i)(config|setting|preference)"), "配置管理"),
            Map.entry(Pattern.compile("(?i)(file|upload|download|storage)"), "文件存储"),
            Map.entry(Pattern.compile("(?i)(search|index|elasticsearch)"), "搜索索引")
    );

    /**
     * 分析代码块的意图
     * 
     * @param chunk 代码块
     * @return 意图描述Map
     */
    public Map<String, List<String>> analyzeIntent(CodeChunk chunk) {
        Map<String, List<String>> intents = new HashMap<>();
        
        // 分析功能意图
        List<String> functionalIntents = analyzeFunctionalIntent(chunk);
        if (!functionalIntents.isEmpty()) {
            intents.put("functional", functionalIntents);
        }
        
        // 分析技术意图
        List<String> technicalIntents = analyzeTechnicalIntent(chunk);
        if (!technicalIntents.isEmpty()) {
            intents.put("technical", technicalIntents);
        }
        
        // 分析业务意图
        List<String> businessIntents = analyzeBusinessIntent(chunk);
        if (!businessIntents.isEmpty()) {
            intents.put("business", businessIntents);
        }
        
        return intents;
    }

    /**
     * 分析功能意图
     */
    private List<String> analyzeFunctionalIntent(CodeChunk chunk) {
        List<String> intents = new ArrayList<>();
        
        // 分析方法名
        if (chunk.getName() != null) {
            for (Map.Entry<Pattern, String> entry : FUNCTIONAL_INTENTS.entrySet()) {
                Matcher matcher = entry.getKey().matcher(chunk.getName());
                if (matcher.find() && !intents.contains(entry.getValue())) {
                    intents.add(entry.getValue());
                }
            }
        }
        
        // 分析代码内容
        if (chunk.getCode() != null) {
            for (Map.Entry<Pattern, String> entry : FUNCTIONAL_INTENTS.entrySet()) {
                Matcher matcher = entry.getKey().matcher(chunk.getCode());
                if (matcher.find() && !intents.contains(entry.getValue())) {
                    intents.add(entry.getValue());
                }
            }
        }
        
        return intents;
    }

    /**
     * 分析技术意图
     */
    private List<String> analyzeTechnicalIntent(CodeChunk chunk) {
        List<String> intents = new ArrayList<>();
        
        // 分析注解
        if (chunk.getAnnotations() != null) {
            for (Map.Entry<Pattern, String> entry : TECHNICAL_INTENTS.entrySet()) {
                Matcher matcher = entry.getKey().matcher(chunk.getAnnotations());
                if (matcher.find() && !intents.contains(entry.getValue())) {
                    intents.add(entry.getValue());
                }
            }
        }
        
        // 分析代码内容
        if (chunk.getCode() != null) {
            for (Map.Entry<Pattern, String> entry : TECHNICAL_INTENTS.entrySet()) {
                Matcher matcher = entry.getKey().matcher(chunk.getCode());
                if (matcher.find() && !intents.contains(entry.getValue())) {
                    intents.add(entry.getValue());
                }
            }
        }
        
        return intents;
    }

    /**
     * 分析业务意图
     */
    private List<String> analyzeBusinessIntent(CodeChunk chunk) {
        List<String> intents = new ArrayList<>();
        
        // 分析包名
        if (chunk.getPackageName() != null) {
            for (Map.Entry<Pattern, String> entry : BUSINESS_INTENTS.entrySet()) {
                Matcher matcher = entry.getKey().matcher(chunk.getPackageName());
                if (matcher.find() && !intents.contains(entry.getValue())) {
                    intents.add(entry.getValue());
                }
            }
        }
        
        // 分析类名/方法名
        if (chunk.getName() != null) {
            for (Map.Entry<Pattern, String> entry : BUSINESS_INTENTS.entrySet()) {
                Matcher matcher = entry.getKey().matcher(chunk.getName());
                if (matcher.find() && !intents.contains(entry.getValue())) {
                    intents.add(entry.getValue());
                }
            }
        }
        
        // 分析描述
        if (chunk.getDescription() != null) {
            for (Map.Entry<Pattern, String> entry : BUSINESS_INTENTS.entrySet()) {
                Matcher matcher = entry.getKey().matcher(chunk.getDescription());
                if (matcher.find() && !intents.contains(entry.getValue())) {
                    intents.add(entry.getValue());
                }
            }
        }
        
        return intents;
    }

    /**
     * 将意图转换为语义文本增强
     * 
     * @param intents 意图Map
     * @return 意图描述文本
     */
    public String intentsToText(Map<String, List<String>> intents) {
        if (intents.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Intents: ");
        
        // 业务意图 (最重要)
        if (intents.containsKey("business")) {
            sb.append("[Business: ").append(String.join(", ", intents.get("business"))).append("] ");
        }
        
        // 功能意图
        if (intents.containsKey("functional")) {
            sb.append("[Functional: ").append(String.join(", ", intents.get("functional"))).append("] ");
        }
        
        // 技术意图
        if (intents.containsKey("technical")) {
            sb.append("[Technical: ").append(String.join(", ", intents.get("technical"))).append("]");
        }
        
        return sb.toString().trim();
    }

    /**
     * 分析代码块并返回增强的语义文本
     */
    public String analyzeAndEnhance(CodeChunk chunk) {
        Map<String, List<String>> intents = analyzeIntent(chunk);
        return intentsToText(intents);
    }
}
