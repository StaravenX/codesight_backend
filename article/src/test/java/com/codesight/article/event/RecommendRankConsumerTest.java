package com.codesight.article.event;

import com.codesight.ai.service.ArticleVectorService;
import com.codesight.article.service.RecommendRankService;
import com.codesight.counter.event.CounterEvent;
import com.codesight.counter.schema.CounterSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendRankConsumerTest {

    @Mock
    private RecommendRankService recommendRankService;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ArticleVectorService articleVectorService;

    @Mock
    private Acknowledgment ack;

    @InjectMocks
    private RecommendRankConsumer consumer;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForSet()).thenReturn(setOperations);
    }

    @Test
    @DisplayName("测试正向点赞行为：推高排序分并沉淀用户正向向量偏好")
    void testPositiveLikeInteraction() {
        CounterEvent event = CounterEvent.of(
                CounterSchema.EntityType.ARTICLE,
                "1001",
                "like",
                1,
                888L,
                1
        );

        consumer.onMessage(event, ack);

        verify(recommendRankService).addOrIncrScore(1001L, 5.0);
        verify(setOperations).add(RecommendRankConsumer.DIRTY_ARTICLES_KEY, "1001");
        verify(articleVectorService).recordFeedback(ArticleVectorService.FeedbackType.POSITIVE, 888L, 1001L);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("测试正向阅读行为：阅读推高基础分并沉淀初级偏好画像")
    void testPositiveViewInteraction() {
        CounterEvent event = CounterEvent.of(
                CounterSchema.EntityType.ARTICLE,
                "1002",
                "views",
                0,
                888L,
                1
        );

        consumer.onMessage(event, ack);

        verify(recommendRankService).addOrIncrScore(1002L, 1.0);
        verify(articleVectorService).recordFeedback(ArticleVectorService.FeedbackType.POSITIVE, 888L, 1002L);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("测试取消点赞反向行为：扣减推荐分值并从正向偏好画像中移除")
    void testCancelLikeInteraction() {
        CounterEvent event = CounterEvent.of(
                CounterSchema.EntityType.ARTICLE,
                "1001",
                "like",
                1,
                888L,
                -1
        );

        consumer.onMessage(event, ack);

        verify(recommendRankService).addOrIncrScore(1001L, -5.0);
        verify(setOperations).add(RecommendRankConsumer.DIRTY_ARTICLES_KEY, "1001");
        verify(articleVectorService, never()).recordFeedback(any(), anyLong(), anyLong());
        verify(articleVectorService, times(1))
                .removeFeedback(ArticleVectorService.FeedbackType.POSITIVE, 888L, 1001L);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("测试取消收藏反向行为：扣减推荐分值并从正向偏好画像中移除")
    void testCancelFavoriteInteraction() {
        CounterEvent event = CounterEvent.of(
                CounterSchema.EntityType.ARTICLE,
                "1001",
                "favorite",
                1,
                888L,
                -1
        );

        consumer.onMessage(event, ack);

        verify(recommendRankService).addOrIncrScore(1001L, -8.0);
        verify(articleVectorService, times(1))
                .removeFeedback(ArticleVectorService.FeedbackType.POSITIVE, 888L, 1001L);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("测试未登录匿名阅读：仅推高文章推荐分，不记录用户画像")
    void testAnonymousViewInteraction() {
        CounterEvent event = CounterEvent.of(
                CounterSchema.EntityType.ARTICLE,
                "1003",
                "views",
                0,
                0L,
                1
        );

        consumer.onMessage(event, ack);

        verify(recommendRankService).addOrIncrScore(1003L, 1.0);
        verify(articleVectorService, never()).recordFeedback(any(), anyLong(), anyLong());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("测试非文章实体事件：直接忽略并 ack")
    void testNonArticleEntityIgnored() {
        CounterEvent event = CounterEvent.of(
                CounterSchema.EntityType.USER,
                "2001",
                "followers",
                2,
                888L,
                1
        );

        consumer.onMessage(event, ack);

        verifyNoInteractions(recommendRankService);
        verifyNoInteractions(articleVectorService);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("测试消费异常容灾保护")
    void testExceptionHandling() {
        CounterEvent event = CounterEvent.of(
                CounterSchema.EntityType.ARTICLE,
                "invalid_id",
                "like",
                1,
                888L,
                1
        );

        consumer.onMessage(event, ack);

        verify(ack).acknowledge();
    }
}
