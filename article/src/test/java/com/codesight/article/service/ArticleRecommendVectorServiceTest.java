package com.codesight.article.service;

import com.codesight.ai.config.AiProperties;
import com.codesight.ai.service.ArticleVectorService;
import com.codesight.article.model.entity.Article;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleRecommendVectorServiceTest {

    @Spy
    private AiProperties aiProperties = new AiProperties();

    @Mock
    private ArticleVectorService articleVectorService;

    @InjectMocks
    private ArticleRecommendVectorService articleRecommendVectorService;

    private Long userId;

    private Article createArticle(Long id, String title, double rankScore) {
        return Article.builder()
                .id(id)
                .title(title)
                .rankScore(rankScore)
                .build();
    }

    @BeforeEach
    void setUp() {
        userId = 666L;
        aiProperties.setPruningEnabled(true);
        aiProperties.setSimilarityThreshold(0.85);
        aiProperties.setRankingEnabled(true);
        aiProperties.setSimilarityWeight(0.4);
        aiProperties.setBaseScoreWeight(0.6);
    }

    @Test
    @DisplayName("重点创新点验证：负向剪枝过滤同质软文，正向精排使契合兴趣的干货跃升第一")
    void testRecommendAndRerank_NegativePruningAndPositiveRerank() {
        Article softA = createArticle(101L, "7天速成年薪百万", 90.0);
        Article softB = createArticle(102L, "零基础小白月薪过万", 80.0);
        Article techC = createArticle(103L, "Java 21 虚拟线程底层原理", 50.0);
        Article techD = createArticle(104L, "MySQL B+Tree 索引底层结构", 70.0);

        List<Article> allCandidates = List.of(softA, softB, techC, techD);

        // 软文 A 与 B 向量高度相似（余弦 > 0.95）
        float[] softA_Vec = new float[]{0.6f, 0.5f, 0.4f, 0.3f};
        float[] softB_Vec = new float[]{0.58f, 0.52f, 0.39f, 0.31f};

        // 硬核技术文 C 与 D 向量
        float[] techC_Vec = new float[]{0.0f, 1.0f, 0.0f};
        float[] techD_Vec = new float[]{1.0f, 0.0f, 0.0f};

        // 用户负向锚点包含软文 A
        when(articleVectorService.batchGetUserFeedbackVector(ArticleVectorService.FeedbackType.NEGATIVE, userId))
                .thenReturn(List.of(softA_Vec));

        // 批量获取文章向量（剪枝与精排均使用）
        when(articleVectorService.batchGetArticleVector(any()))
                .thenReturn(Map.of(
                        101L, softA_Vec,
                        102L, softB_Vec,
                        103L, techC_Vec,
                        104L, techD_Vec
                ));

        // 模拟用户正向兴趣向量（偏好 Java 虚拟线程）
        float[] userInterest = new float[]{0.0f, 1.0f, 0.0f};
        when(articleVectorService.getUserInterestVector(userId)).thenReturn(userInterest);

        List<Article> result = articleRecommendVectorService.recommendAndRerank(userId, allCandidates);

        // 校验结果：
        // 1. 软文 A 与同质软文 B 被负向剪枝剔除
        assertEquals(2, result.size());
        assertFalse(result.stream().anyMatch(a -> a.getId().equals(101L)));
        assertFalse(result.stream().anyMatch(a -> a.getId().equals(102L)));

        // 2. 原热度分仅 50.0 的 techC，因与用户正向兴趣画像高度契合，跃升为第一名
        assertEquals(103L, result.getFirst().getId(), "兴趣高度契合的 techC 应由于正向加权升至第一位");
        assertEquals(104L, result.get(1).getId());
    }

    @Test
    @DisplayName("测试单向负反馈剪枝：仅有负反馈锚点时剔除同质内容并保留原序")
    void testRecommendAndRerank_OnlyNegativePruning_NoUserInterest() {
        float[] softA = new float[]{0.6f, 0.5f, 0.4f, 0.3f};
        float[] softB = new float[]{0.58f, 0.52f, 0.39f, 0.31f};
        float[] techC = new float[]{-0.5f, 0.6f, -0.4f, 0.2f};

        when(articleVectorService.batchGetUserFeedbackVector(ArticleVectorService.FeedbackType.NEGATIVE, userId))
                .thenReturn(List.of(softA));
        when(articleVectorService.getUserInterestVector(userId))
                .thenReturn(null);
        when(articleVectorService.batchGetArticleVector(any()))
                .thenReturn(Map.of(101L, softA, 102L, softB, 103L, techC));

        Article a = createArticle(101L, "软文A", 10.0);
        Article b = createArticle(102L, "软文B", 20.0);
        Article c = createArticle(103L, "硬核C", 30.0);

        List<Article> result = articleRecommendVectorService.recommendAndRerank(userId, List.of(a, b, c));

        assertEquals(1, result.size());
        assertEquals(103L, result.getFirst().getId());
    }

    @Test
    @DisplayName("测试冷启动用户（无正向兴趣向量）：平滑降级为剪枝后原序")
    void testRecommendAndRerank_ColdStartUser_FallbackToOriginal() {
        Article techC = createArticle(103L, "Java 并发", 50.0);
        Article techD = createArticle(104L, "MySQL 调优", 85.0);
        List<Article> candidates = List.of(techC, techD);

        when(articleVectorService.batchGetUserFeedbackVector(ArticleVectorService.FeedbackType.NEGATIVE, userId))
                .thenReturn(Collections.emptyList());
        when(articleVectorService.getUserInterestVector(userId))
                .thenReturn(new float[0]);

        List<Article> result = articleRecommendVectorService.recommendAndRerank(userId, candidates);

        assertEquals(2, result.size());
        assertEquals(103L, result.get(0).getId());
        assertEquals(104L, result.get(1).getId());
    }

    @Test
    @DisplayName("测试未登录或功能关闭场景：原样直通返回")
    void testRecommendAndRerank_DisabledOrUnauthenticated() {
        Article a = createArticle(101L, "A", 10.0);
        List<Article> candidates = List.of(a);

        // 未登录
        List<Article> r1 = articleRecommendVectorService.recommendAndRerank(null, candidates);
        assertSame(candidates, r1);

        // 功能开关关闭
        aiProperties.setPruningEnabled(false);
        aiProperties.setRankingEnabled(false);
        List<Article> r2 = articleRecommendVectorService.recommendAndRerank(userId, candidates);
        assertSame(candidates, r2);
    }
}
