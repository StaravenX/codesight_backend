package com.codesight.article.task;

import com.codesight.article.event.RecommendRankConsumer;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.service.RecommendRankService;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleCounterPersistenceTaskTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private SetOperations<String, String> setOps;

    @Mock
    private CounterService counterService;

    @Mock
    private RecommendRankService recommendRankService;

    @Mock
    private ArticleMapper articleMapper;

    @InjectMocks
    private ArticleCounterPersistenceTask persistenceTask;

    @Test
    @DisplayName("测试异步落盘任务 - 正常微批弹出并批量持久化回写 MySQL")
    void testFlushDirtyArticlesToDb_Success() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.pop(RecommendRankConsumer.DIRTY_ARTICLES_KEY, 200)).thenReturn(List.of("1001", "1002"));

        Map<CounterSchema.MetricItem, Long> counts1001 = Map.of(
                CounterSchema.ArticleMetric.VIEWS, 150L,
                CounterSchema.ArticleMetric.LIKE, 20L,
                CounterSchema.ArticleMetric.COMMENT, 5L,
                CounterSchema.ArticleMetric.FAVORITE, 8L
        );
        Map<String, Map<CounterSchema.MetricItem, Long>> countsMap = Map.of("1001", counts1001);
        when(counterService.batchGetCounts(eq(CounterSchema.EntityType.ARTICLE), anyList())).thenReturn(countsMap);

        Map<Long, Double> scoreMap = Map.of(1001L, 95.5, 1002L, 10.0);
        when(recommendRankService.batchGetScores(anyList())).thenReturn(scoreMap);

        when(articleMapper.updateById(any(Article.class))).thenReturn(1);

        persistenceTask.flushDirtyArticlesToDb();

        ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
        verify(articleMapper, times(2)).updateById(captor.capture());

        List<Article> updated = captor.getAllValues();
        Article first = updated.getFirst();
        assertEquals(1001L, first.getId());
        assertEquals(150L, first.getViewCount());
        assertEquals(20L, first.getLikeCount());
        assertEquals(95.5, first.getRankScore());

        verify(setOps, never()).add(eq(RecommendRankConsumer.DIRTY_ARTICLES_KEY), any(String[].class));
    }

    @Test
    @DisplayName("测试异步落盘任务 - 单条更新失败时将失败 ID 重放回脏标记池重试")
    void testFlushDirtyArticlesToDb_FailureRetry() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.pop(RecommendRankConsumer.DIRTY_ARTICLES_KEY, 200)).thenReturn(List.of("1001", "1002"));

        when(counterService.batchGetCounts(any(), anyList())).thenReturn(Map.of());
        when(recommendRankService.batchGetScores(anyList())).thenReturn(Map.of(1001L, 80.0, 1002L, 50.0));

        // 1001 成功，1002 模拟数据库更新异常
        when(articleMapper.updateById(any(Article.class))).thenAnswer(invocation -> {
            Article a = invocation.getArgument(0);
            if (Long.valueOf(1002L).equals(a.getId())) {
                throw new RuntimeException("DB Connection Timeout");
            }
            return 1;
        });

        persistenceTask.flushDirtyArticlesToDb();

        // 验证失败的 1002 被重新丢回脏池等待下一次落盘
        verify(setOps).add(RecommendRankConsumer.DIRTY_ARTICLES_KEY, "1002");
    }

    @Test
    @DisplayName("测试异步落盘任务 - 脏池为空时直接返回无后续 I/O")
    void testFlushDirtyArticlesToDb_EmptyPool() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.pop(RecommendRankConsumer.DIRTY_ARTICLES_KEY, 200)).thenReturn(List.of());

        persistenceTask.flushDirtyArticlesToDb();

        verifyNoInteractions(counterService);
        verifyNoInteractions(recommendRankService);
        verifyNoInteractions(articleMapper);
    }
}
