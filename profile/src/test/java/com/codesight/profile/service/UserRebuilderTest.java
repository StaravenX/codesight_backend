package com.codesight.profile.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.relation.mapper.UserFollowerMapper;
import com.codesight.relation.mapper.UserFollowingMapper;
import com.codesight.relation.model.UserFollower;
import com.codesight.relation.model.UserFollowing;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserRebuilder 创作者计数自愈重建单元测试")
class UserRebuilderTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
        TableInfoHelper.initTableInfo(assistant, UserFollower.class);
        TableInfoHelper.initTableInfo(assistant, UserFollowing.class);
    }

    @Mock
    private CounterService counterService;

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private UserFollowerMapper userFollowerMapper;

    @Mock
    private UserFollowingMapper userFollowingMapper;

    @InjectMocks
    private UserRebuilder userRebuilder;

    @Test
    @DisplayName("测试重建：粉丝/关注走位图真值，总阅读/总获赞优先聚合文章实时 16B SDS")
    void testRebuildAggregatesRealTimeArticleSds() {
        // 1. 模拟粉丝数与关注数位图真值
        when(counterService.bitCountShards(CounterSchema.EntityType.USER, "888", CounterSchema.UserMetric.FOLLOWERS))
                .thenReturn(120L);
        when(counterService.bitCountShards(CounterSchema.EntityType.USER, "888", CounterSchema.UserMetric.FOLLOWINGS))
                .thenReturn(45L);

        // 2. 模拟底表文章列表（MySQL viewCount 滞后为 100，likeCount 滞后为 10）
        Article a1 = new Article();
        a1.setId(101L);
        a1.setViewCount(100L);
        a1.setLikeCount(10L);

        Article a2 = new Article();
        a2.setId(102L);
        a2.setViewCount(200L);
        a2.setLikeCount(20L);

        when(articleMapper.selectList(any())).thenReturn(List.of(a1, a2));

        // 3. 模拟 Redis 16B SDS 实时计数（a1 实时 views=500, like=60; a2 实时 views=800, like=90）
        Map<String, Map<CounterSchema.MetricItem, Long>> realTimeMap = getRealTimeMap();
        when(counterService.batchGetCounts(eq(CounterSchema.EntityType.ARTICLE), eq(List.of("101", "102"))))
                .thenReturn(realTimeMap);

        Map<CounterSchema.MetricItem, Long> result = userRebuilder.rebuild("888");

        assertNotNull(result);
        assertEquals(120L, result.get(CounterSchema.UserMetric.FOLLOWERS));
        assertEquals(45L, result.get(CounterSchema.UserMetric.FOLLOWINGS));
        // 总阅读 = 500 + 800 = 1300（而非 MySQL 落后值的 100 + 200 = 300）
        assertEquals(1300L, result.get(CounterSchema.UserMetric.VIEWS_RECEIVED));
        // 总获赞 = 60 + 90 = 150（而非 MySQL 落后值的 10 + 20 = 30）
        assertEquals(150L, result.get(CounterSchema.UserMetric.LIKES_RECEIVED));
    }

    private static @NonNull Map<String, Map<CounterSchema.MetricItem, Long>> getRealTimeMap() {
        Map<CounterSchema.MetricItem, Long> a1Counts = Map.of(
                CounterSchema.ArticleMetric.VIEWS, 500L,
                CounterSchema.ArticleMetric.LIKE, 60L
        );
        Map<CounterSchema.MetricItem, Long> a2Counts = Map.of(
                CounterSchema.ArticleMetric.VIEWS, 800L,
                CounterSchema.ArticleMetric.LIKE, 90L
        );
        return Map.of(
                "101", a1Counts,
                "102", a2Counts
        );
    }

    @Test
    @DisplayName("测试降级重建：Redis 文章实时 SDS 缺失时降级采用 MySQL 存盘基线")
    void testRebuildFallbackToMysqlWhenSdsMissing() {
        when(counterService.bitCountShards(any(), anyString(), any()))
                .thenReturn(50L);

        Article a1 = new Article();
        a1.setId(101L);
        a1.setViewCount(300L);
        a1.setLikeCount(40L);

        when(articleMapper.selectList(any())).thenReturn(List.of(a1));
        when(counterService.batchGetCounts(any(), anyList())).thenReturn(Collections.emptyMap());

        Map<CounterSchema.MetricItem, Long> result = userRebuilder.rebuild("888");

        assertNotNull(result);
        assertEquals(300L, result.get(CounterSchema.UserMetric.VIEWS_RECEIVED));
        assertEquals(40L, result.get(CounterSchema.UserMetric.LIKES_RECEIVED));
    }
}
