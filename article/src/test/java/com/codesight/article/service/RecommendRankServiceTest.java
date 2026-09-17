package com.codesight.article.service;

import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
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

    @Mock
    private ArticleMapper articleMapper;

    private RecommendRankService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForZSet()).thenReturn(zSetOperations);
        service = new RecommendRankService(redis, articleMapper);
    }

    @Test
    @DisplayName("测试首次入池：新文章（rankScore=0）赋予基础起跑分 + 增量分")
    void testAddOrIncrScoreNewItem() {
        when(zSetOperations.score(RecommendRankService.RECOMMEND_POOL_KEY, "1001")).thenReturn(null);
        when(articleMapper.selectById(1001L)).thenReturn(Article.builder()
                .id(1001L)
                .status(ArticleStatus.PUBLISHED)
                .rankScore(0.0)
                .build());

        service.addOrIncrScore(1001L, 5.0);

        verify(zSetOperations).add(RecommendRankService.RECOMMEND_POOL_KEY, "1001", 15.0);
    }

    @Test
    @DisplayName("测试沉寂爆款唤醒：继承历史真实高分并累加增量分")
    void testAddOrIncrScore_AwakenHistoricalHotArticle() {
        when(zSetOperations.score(RecommendRankService.RECOMMEND_POOL_KEY, "1001")).thenReturn(null);
        when(articleMapper.selectById(1001L)).thenReturn(Article.builder()
                .id(1001L)
                .status(ArticleStatus.PUBLISHED)
                .rankScore(120.0)
                .build());

        service.addOrIncrScore(1001L, 5.0);

        // 120.0 + 5.0 = 125.0
        verify(zSetOperations).add(RecommendRankService.RECOMMEND_POOL_KEY, "1001", 125.0);
    }

    @Test
    @DisplayName("测试拦截非公开文章：草稿或已删除文章严禁被唤醒入池")
    void testAddOrIncrScore_InterceptDraftArticle() {
        when(zSetOperations.score(RecommendRankService.RECOMMEND_POOL_KEY, "1001")).thenReturn(null);
        when(articleMapper.selectById(1001L)).thenReturn(Article.builder()
                .id(1001L)
                .status(ArticleStatus.DRAFT)
                .build());

        service.addOrIncrScore(1001L, 5.0);

        verify(zSetOperations, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("测试已有文章：执行原子自增")
    void testAddOrIncrScoreExistingItem() {
        when(zSetOperations.score(RecommendRankService.RECOMMEND_POOL_KEY, "1001")).thenReturn(20.0);
        when(zSetOperations.incrementScore(RecommendRankService.RECOMMEND_POOL_KEY, "1001", 5.0)).thenReturn(25.0);

        service.addOrIncrScore(1001L, 5.0);

        verify(zSetOperations).incrementScore(RecommendRankService.RECOMMEND_POOL_KEY, "1001", 5.0);
    }

    @Test
    @DisplayName("防反向唤醒：沉寂出池文章遇到负向互动（如取消点赞）严禁反向入池")
    void testAddOrIncrScore_NegativeDeltaWhenOutOfPool_ShouldNotWakeUp() {
        when(zSetOperations.score(RecommendRankService.RECOMMEND_POOL_KEY, "1001")).thenReturn(null);

        // 用户取消点赞产生了负增量 -5.0
        service.addOrIncrScore(1001L, -5.0);

        // 验证绝对不能执行 add 入池操作
        verify(zSetOperations, never()).add(anyString(), anyString(), anyDouble());
        verify(zSetOperations, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("池内扣分清退：池内文章扣减后分值跌破推荐池下限，自动物理移除")
    void testAddOrIncrScore_ScoreDecreasesBelowThreshold_ShouldRemove() {
        when(zSetOperations.score(RecommendRankService.RECOMMEND_POOL_KEY, "1001")).thenReturn(3.0);
        when(zSetOperations.incrementScore(RecommendRankService.RECOMMEND_POOL_KEY, "1001", -5.0)).thenReturn(0.8);

        service.addOrIncrScore(1001L, -5.0);

        verify(zSetOperations).incrementScore(RecommendRankService.RECOMMEND_POOL_KEY, "1001", -5.0);
        verify(zSetOperations).remove(RecommendRankService.RECOMMEND_POOL_KEY, "1001");
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
                eq("3000"),
                eq("200")
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

    @Test
    @DisplayName("测试容量自愈回灌：容量充盈时不触发回灌")
    void testRefillPoolIfLow_WhenSufficient_ShouldSkip() {
        when(zSetOperations.zCard(RecommendRankService.RECOMMEND_POOL_KEY)).thenReturn(600L);

        boolean refilled = service.refillPoolIfLow(500, 3000);

        assertFalse(refilled);
        verify(articleMapper, never()).selectList(any());
        verify(zSetOperations, never()).add(anyString(), anySet());
    }

    @Test
    @DisplayName("测试容量自愈回灌：容量不足警戒线时自动从数据库捞取文章批量注满")
    void testRefillPoolIfLow_WhenLow_ShouldRefill() {
        when(zSetOperations.zCard(RecommendRankService.RECOMMEND_POOL_KEY)).thenReturn(80L);
        Article a = Article.builder().id(5001L).rankScore(90.0).status(ArticleStatus.PUBLISHED).visible(ArticleVisible.PUBLIC).build();
        when(articleMapper.selectList(any())).thenReturn(List.of(a));

        boolean refilled = service.refillPoolIfLow(100, 3000);

        assertTrue(refilled);
        verify(articleMapper).selectList(any());
        verify(zSetOperations).add(eq(RecommendRankService.RECOMMEND_POOL_KEY), anySet());
    }
}
