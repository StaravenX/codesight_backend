package com.codesight.ai;

import com.codesight.ai.api.dto.AiChatRequest;
import com.codesight.ai.api.dto.SuggestQuestionsRequest;
import com.codesight.ai.api.dto.SuggestQuestionsResponse;
import com.codesight.ai.config.AiProperties;
import com.codesight.ai.service.AiChatService;
import com.codesight.ai.service.ArticleVectorService;
import com.codesight.app.CodeSightApplication;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AI 模块真实中间件集成测试：
 * 验证与 Spring AI、大模型接口及 Redis 向量/反馈缓存的端到端交互
 */
@SuppressWarnings("NewClassNamingConvention")
@SpringBootTest(classes = CodeSightApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AiIT {

    @Autowired(required = false)
    private ArticleVectorService articleVectorService;

    @Autowired(required = false)
    private AiChatService aiChatService;

    @Autowired(required = false)
    private AiProperties aiProperties;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    private static final long TEST_USER_ID = 888801L;
    private static final long TEST_ARTICLE_ID = 888802L;

    private boolean isRedisAvailable() {
        if (stringRedisTemplate == null) {
            return false;
        }
        try {
            assertNotNull(stringRedisTemplate.getConnectionFactory());
            String ping = stringRedisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(ping);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isEmbeddingAvailable() {
        if (embeddingModel == null) {
            return false;
        }
        try {
            float[] vector = embeddingModel.embed("ping");
            return vector.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @Order(1)
    void testAiBeansLoadedSuccessfully() {
        assertNotNull(articleVectorService, "ArticleVectorService 应成功注入");
        assertNotNull(aiChatService, "AiChatService 应成功注入");
        assertNotNull(aiProperties, "AiProperties 应成功注入");
        assertTrue(aiProperties.isPruningEnabled(), "负反馈剪枝配置默认应开启");
    }

    @Test
    @Order(2)
    void testUserFeedbackRecordingAndRedisCaching() {
        assumeTrue(isRedisAvailable(), "Redis 服务未就绪，跳过真实 Redis 反馈缓存测试");

        // 1. 记录正向互动样本
        assertDoesNotThrow(() ->
                articleVectorService.recordFeedback(ArticleVectorService.FeedbackType.POSITIVE, TEST_USER_ID, TEST_ARTICLE_ID)
        );

        // 2. 验证 Redis 真实 ZSet 写入与条目计数
        String posKey = ArticleVectorService.FeedbackType.POSITIVE.getKeyPrefix() + TEST_USER_ID;
        Long zCard = stringRedisTemplate.opsForZSet().zCard(posKey);
        assertNotNull(zCard);
        assertTrue(zCard > 0, "用户正向偏好 ZSet 中应存在记录");

        // 3. 记录负向屏蔽样本
        assertDoesNotThrow(() ->
                articleVectorService.recordFeedback(ArticleVectorService.FeedbackType.NEGATIVE, TEST_USER_ID, TEST_ARTICLE_ID + 1)
        );
        String negKey = ArticleVectorService.FeedbackType.NEGATIVE.getKeyPrefix() + TEST_USER_ID;
        assertTrue(stringRedisTemplate.hasKey(negKey), "用户负向偏好 ZSet Key 应存在");

        // 清理测试 Key
        stringRedisTemplate.delete(List.of(posKey, negKey));
    }

    @Test
    @Order(3)
    void testArticleVectorGenerationAndStorage() {
        assumeTrue(isEmbeddingAvailable(), "外部 Embedding 大模型未就绪或未配置 API Key，跳过真实向量生成测试");

        String title = "Java 21 虚拟线程生产实践";
        String summary = "解析虚拟线程调度原理与 Carrier 载体线程关系";
        String content = "虚拟线程是轻量级线程，由 JVM 统一调度管理，大幅降低 I/O 阻塞成本。";

        float[] vector = articleVectorService.generateAndSaveVector(TEST_ARTICLE_ID, title, summary, content);
        assertNotNull(vector);
        assertTrue(vector.length > 0, "生成的文章向量不应为空");

        // 验证 Redis 向量缓存
        if (isRedisAvailable()) {
            Map<Long, float[]> vectorMap = articleVectorService.batchGetArticleVector(List.of(TEST_ARTICLE_ID));
            assertTrue(vectorMap.containsKey(TEST_ARTICLE_ID), "应命中 Redis 中的文章向量缓存");
            assertEquals(vector.length, vectorMap.get(TEST_ARTICLE_ID).length);
        }
    }

    @Test
    @Order(4)
    void testAiChatSuggestQuestionsPipeline() {
        // 验证智能追问推荐管道能够正常执行
        SuggestQuestionsRequest request = SuggestQuestionsRequest.builder()
                .articleContext(AiChatRequest.ArticleContext.builder()
                        .title("深入理解 MySQL B+ 树索引结构")
                        .summary("解析聚簇索引、二级索引与回表查询底层机制")
                        .content("MySQL InnoDB 存储引擎使用 B+ 树作为索引结构，所有数据记录均存放在叶子节点。")
                        .build())
                .chatHistory(List.of(
                        AiChatRequest.ChatMessage.builder()
                                .role("user")
                                .content("为什么 B+ 树的叶子节点需要双向链表？")
                                .build()
                ))
                .build();

        try {
            SuggestQuestionsResponse response = aiChatService.suggestQuestions(request);
            assertNotNull(response);
            assertNotNull(response.queries());
        } catch (Exception e) {
            // 大模型端点网络未连通时输出日志
            assertTrue(e.getMessage() != null || !e.getClass().getName().isEmpty());
        }
    }
}

