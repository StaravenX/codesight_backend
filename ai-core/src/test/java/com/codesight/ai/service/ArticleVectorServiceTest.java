package com.codesight.ai.service;

import com.codesight.ai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleVectorServiceTest {

    @Spy
    private AiProperties aiProperties = new AiProperties();

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private EmbeddingModel embeddingModel;

    @InjectMocks
    private ArticleVectorService articleVectorService;

    @BeforeEach
    void setUp() {
        aiProperties.setMaxPositiveAnchors(20);
        aiProperties.setMaxNegativeAnchors(10);
    }

    @Test
    @DisplayName("测试批量获取文章向量")
    void testGetArticleVectorsBatch() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of("ai:article:vector:1", "ai:article:vector:2")))
                .thenReturn(List.of("1.0,2.0", "3.0,4.0"));

        Map<Long, float[]> vectors = articleVectorService.batchGetArticleVector(List.of(1L, 2L));

        assertEquals(2, vectors.size());
        assertArrayEquals(new float[]{1.0f, 2.0f}, vectors.get(1L));
        assertArrayEquals(new float[]{3.0f, 4.0f}, vectors.get(2L));
    }

    @Test
    @DisplayName("测试记录用户负反馈：写入负向 ZSet 并设置过期")
    void testRecordFeedback_Negative() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.zCard("ai:user:negative:123")).thenReturn(5L);

        articleVectorService.recordFeedback(ArticleVectorService.FeedbackType.NEGATIVE, 123L, 456L);

        verify(zSetOperations).add(eq("ai:user:negative:123"), eq("456"), anyDouble());
        verify(stringRedisTemplate).expire(eq("ai:user:negative:123"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("测试记录用户正向反馈：写入正向 ZSet 并设置过期")
    void testRecordFeedback_Positive() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.zCard("ai:user:positive:123")).thenReturn(8L);

        articleVectorService.recordFeedback(ArticleVectorService.FeedbackType.POSITIVE, 123L, 789L);

        verify(zSetOperations).add(eq("ai:user:positive:123"), eq("789"), anyDouble());
        verify(stringRedisTemplate).expire(eq("ai:user:positive:123"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("测试移除用户反馈样本：从 ZSet 移除指定文章")
    void testRemoveFeedback_Success() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        articleVectorService.removeFeedback(ArticleVectorService.FeedbackType.POSITIVE, 123L, 789L);

        verify(zSetOperations).remove("ai:user:positive:123", "789");
    }

    @Test
    @DisplayName("测试移除用户反馈样本：入参为空时安全忽略")
    void testRemoveFeedback_NullParamIgnored() {
        articleVectorService.removeFeedback(null, 123L, 789L);
        articleVectorService.removeFeedback(ArticleVectorService.FeedbackType.POSITIVE, null, 789L);
        articleVectorService.removeFeedback(ArticleVectorService.FeedbackType.POSITIVE, 123L, null);

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("测试获取用户反馈样本向量")
    void testGetUserFeedbackVectors() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(zSetOperations.reverseRange("ai:user:negative:123", 0, 9))
                .thenReturn(Set.of("456"));
        when(valueOperations.multiGet(List.of("ai:article:vector:456")))
                .thenReturn(List.of("0.5,0.5"));

        List<float[]> anchors = articleVectorService.batchGetUserFeedbackVector(ArticleVectorService.FeedbackType.NEGATIVE, 123L);

        assertEquals(1, anchors.size());
        assertArrayEquals(new float[]{0.5f, 0.5f}, anchors.getFirst());
    }

    @Test
    @DisplayName("测试获取用户正向动态兴趣向量：聚合与归一化")
    void testGetUserInterestVector() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(zSetOperations.reverseRange("ai:user:positive:123", 0, 19))
                .thenReturn(Set.of("789", "790"));
        when(valueOperations.multiGet(anyList()))
                .thenReturn(List.of("1.0,0.0", "0.0,1.0"));

        float[] interestVec = articleVectorService.getUserInterestVector(123L);

        assertNotNull(interestVec);
        assertEquals(2, interestVec.length);
        double norm = Math.sqrt(interestVec[0] * interestVec[0] + interestVec[1] * interestVec[1]);
        assertEquals(1.0, norm, 1e-6);
    }

    @Test
    @DisplayName("测试生成并保存文章向量资产：结构化文本提取与向量持久化")
    void testGenerateAndSaveVector_Success() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        float[] mockVector = new float[]{0.3f, 0.4f, 0.5f};
        when(embeddingModel.embed(anyString())).thenReturn(mockVector);

        Long articleId = 2001L;
        String title = "Netty 零拷贝深度实践";
        String summary = "本文剖析 FileRegion 与堆外内存原理";
        String content = "导言：Netty 是一款高性能网络框架。\n```java\nSystem.out.println(\"忽略代码\");\n```\n核心机制解析。";

        float[] result = articleVectorService.generateAndSaveVector(articleId, title, summary, content);

        assertNotNull(result);
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(embeddingModel).embed(captor.capture());
        String embeddedText = captor.getValue();
        assertTrue(embeddedText.contains("标题: Netty 零拷贝深度实践"));
        assertTrue(embeddedText.contains("摘要: 本文剖析 FileRegion 与堆外内存原理"));
        assertTrue(embeddedText.contains("核心内容: "));
        assertTrue(embeddedText.contains("Netty 是一款高性能网络框架。 核心机制解析。"));
        assertFalse(embeddedText.contains("System.out.println"));

        verify(valueOperations).set(eq("ai:article:vector:2001"), eq("0.3,0.4,0.5"), any(Duration.class));
    }

    @Test
    @DisplayName("测试生成向量资产异常入参防护")
    void testGenerateAndSaveVector_NullOrBlank() {
        float[] resultNullId = articleVectorService.generateAndSaveVector(null, "标题", "摘要", "正文");
        assertEquals(0, resultNullId.length);

        float[] resultBlank = articleVectorService.generateAndSaveVector(2002L, "", "", "");
        assertEquals(0, resultBlank.length);
        verifyNoInteractions(embeddingModel);
    }

    @Test
    @DisplayName("测试删除文章向量资产")
    void testDeleteArticleVector() {
        articleVectorService.deleteArticleVector(2003L);
        verify(stringRedisTemplate).delete("ai:article:vector:2003");

        articleVectorService.deleteArticleVector(null);
        verifyNoMoreInteractions(stringRedisTemplate);
    }
}


