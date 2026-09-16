package com.codesight.article.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendRankService 推荐候选池单元测试")
class RecommendRankServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private RecommendRankService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForZSet()).thenReturn(zSetOperations);
        service = new RecommendRankService(redis);
    }

    @Test
    @DisplayName("测试首次入池：赋予基础起跑分 + 增量分")
    void testAddOrIncrScoreNewItem() {
        when(zSetOperations.score(RecommendRankService.RECOMMEND_POOL_KEY, "1001")).thenReturn(null);

        service.addOrIncrScore(1001L, 5.0);

        verify(zSetOperations).add(RecommendRankService.RECOMMEND_POOL_KEY, "1001", 15.0);
    }

    @Test
    @DisplayName("测试已有文章：执行原子自增")
    void testAddOrIncrScoreExistingItem() {
        when(zSetOperations.score(RecommendRankService.RECOMMEND_POOL_KEY, "1001")).thenReturn(20.0);

        service.addOrIncrScore(1001L, 5.0);

        verify(zSetOperations).incrementScore(RecommendRankService.RECOMMEND_POOL_KEY, "1001", 5.0);
    }

    @Test
    @DisplayName("测试首屏拉取推荐池：直接按绝对排位切片")
    void testGetRankedArticleIds_FirstPage() {
        Set<TypedTuple<String>> mockTuples = new LinkedHashSet<>();
        mockTuples.add(new DefaultTypedTuple<>("1001", 90.0));
        mockTuples.add(new DefaultTypedTuple<>("1002", 80.0));

        when(zSetOperations.reverseRangeWithScores(
                eq(RecommendRankService.RECOMMEND_POOL_KEY),
                eq(0L),
                eq(9L)
        )).thenReturn(mockTuples);

        List<TypedTuple<String>> result = service.getRankedArticleIds(null, null, 10);

        assertEquals(2, result.size());
        assertEquals("1001", result.getFirst().getValue());
    }

    @Test
    @DisplayName("测试翻页拉取推荐池：基于文章绝对排位向后切片")
    void testGetRankedArticleIds_NextPage_ByRank() {
        Long lastArticleId = 1002L;
        Set<TypedTuple<String>> mockTuples = new LinkedHashSet<>();
        mockTuples.add(new DefaultTypedTuple<>("1003", 80.0)); // 与 1002 同分，天然保留
        mockTuples.add(new DefaultTypedTuple<>("1004", 75.0));

        when(zSetOperations.reverseRank(RecommendRankService.RECOMMEND_POOL_KEY, "1002")).thenReturn(9L);
        when(zSetOperations.reverseRangeWithScores(
                eq(RecommendRankService.RECOMMEND_POOL_KEY),
                eq(10L),
                eq(19L)
        )).thenReturn(mockTuples);

        List<TypedTuple<String>> result = service.getRankedArticleIds(80.0, lastArticleId, 10);

        assertEquals(2, result.size());
        assertEquals("1003", result.getFirst().getValue());
        assertEquals(80.0, result.getFirst().getScore());
    }

    @Test
    @DisplayName("测试翻页拉取推荐池：文章已出池时降级按分数截断")
    void testGetRankedArticleIds_NextPage_FallbackWhenOutOfPool() {
        Long lastArticleId = 1002L;
        Set<TypedTuple<String>> mockTuples = new LinkedHashSet<>();
        mockTuples.add(new DefaultTypedTuple<>("1003", 75.0));

        when(zSetOperations.reverseRank(RecommendRankService.RECOMMEND_POOL_KEY, "1002")).thenReturn(null);
        when(zSetOperations.reverseRangeByScoreWithScores(
                eq(RecommendRankService.RECOMMEND_POOL_KEY),
                eq(0.0),
                eq(80.0),
                eq(0L),
                eq(10L)
        )).thenReturn(mockTuples);

        List<TypedTuple<String>> result = service.getRankedArticleIds(80.0, lastArticleId, 10);

        assertEquals(1, result.size());
        assertEquals("1003", result.getFirst().getValue());
    }



    @Test
    @DisplayName("测试半衰期降温调用 Lua 脚本")
    void testDecayAll() {
        service.decayAll(0.9, 3000);

        verify(redis).execute(
                any(RedisScript.class),
                eq(List.of(RecommendRankService.RECOMMEND_POOL_KEY)),
                eq("0.9"),
                eq("3000")
        );
    }

    @Test
    @DisplayName("测试批量回填文章到推荐候选池")
    void testBatchAddScores() {
        com.codesight.article.model.entity.Article a1 = com.codesight.article.model.entity.Article.builder()
                .id(2001L)
                .rankScore(55.0)
                .build();
        com.codesight.article.model.entity.Article a2 = com.codesight.article.model.entity.Article.builder()
                .id(2002L)
                .rankScore(0.0) // 0 分使用起跑底分 BASE_INITIAL_SCORE
                .build();

        service.batchAddScores(List.of(a1, a2));

        ArgumentCaptor<Set> captor = ArgumentCaptor.forClass(Set.class);
        verify(zSetOperations).add(eq(RecommendRankService.RECOMMEND_POOL_KEY), captor.capture());

        Set<TypedTuple<String>> captured = captor.getValue();
        assertEquals(2, captured.size());
    }

    @Test
    @DisplayName("测试文章下架/删除从候选池移除")
    void testRemoveArticle() {
        service.removeArticle(3001L);

        verify(zSetOperations).remove(RecommendRankService.RECOMMEND_POOL_KEY, "3001");
    }
}
