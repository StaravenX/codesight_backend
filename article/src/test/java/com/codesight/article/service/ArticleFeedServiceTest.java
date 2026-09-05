package com.codesight.article.service;

import com.codesight.article.api.dto.request.ArticleFeedRequest;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.article.api.dto.response.ArticleFeedPageResponse;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.mapper.ArticleTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.entity.ArticleTagRel;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import com.codesight.article.model.enums.FeedSortType;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.user.User;
import com.codesight.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleFeedService 信息流与相关推荐单元测试")
class ArticleFeedServiceTest {

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleTagRelMapper articleTagRelMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private UserService userService;

    @Mock
    private CounterService counterService;

    @Mock
    private RecommendRankService recommendRankService;

    private ArticleFeedService articleFeedService;

    @BeforeEach
    void setUp() {
        articleFeedService = new ArticleFeedService(
                articleMapper,
                articleTagRelMapper,
                tagMapper,
                userService,
                counterService,
                recommendRankService
        );
    }

    private Article createArticle(Long id, Long authorId, Long categoryId, Instant publishTime, Long views, Long likes) {
        return Article.builder()
                .id(id)
                .authorId(authorId)
                .categoryId(categoryId)
                .title("文章标题 " + id)
                .summary("文章摘要 " + id)
                .coverUrl("https://example.com/cover/" + id + ".jpg")
                .status(ArticleStatus.PUBLISHED)
                .visible(ArticleVisible.PUBLIC)
                .publishTime(publishTime)
                .viewCount(views)
                .likeCount(likes)
                .commentCount(10L)
                .favoriteCount(5L)
                .rankScore((double) (views + likes * 5 + 10L * 10 + 5L * 8))
                .isTop(false)
                .build();
    }

    @Test
    @DisplayName("测试最新信息流（首屏无游标）：返回 20 条，计算 hasMore=true 与有效 nextCursor")
    void testFeedNewestFirstPage() {
        Instant now = Instant.now();
        List<Article> mockList = new ArrayList<>();
        // 模拟数据库返回 21 条（limitSize = size + 1）
        for (long i = 1; i <= 21; i++) {
            mockList.add(createArticle(i, 100L, 1L, now.minusSeconds(i * 60), i * 10, i * 2));
        }

        when(articleMapper.selectFeedNewest(isNull(), isNull(), isNull(), isNull(), isNull(), eq(21)))
                .thenReturn(mockList);

        User author = User.builder().id(100L).nickname("极客作者").avatar("https://example.com/a.png").jobTitle("架构师").company("测试公司").build();
        when(userService.listByIds(anySet())).thenReturn(List.of(author));

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .size(20)
                .sortBy(FeedSortType.NEWEST)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, null);

        assertNotNull(response);
        assertTrue(response.hasMore());
        assertEquals(20, response.items().size());
        assertNotNull(response.nextCursor());

        // 验证游标可逆解析
        String decodedCursor = new String(Base64.getUrlDecoder().decode(response.nextCursor()), StandardCharsets.UTF_8);
        assertTrue(decodedCursor.startsWith("new:"));
        assertTrue(decodedCursor.contains(":20")); // 第 20 条的 ID

        // 验证首条卡片属性装配
        ArticleFeedItemResponse firstItem = response.items().getFirst();
        assertEquals(1L, firstItem.getId());
        assertEquals("极客作者", firstItem.getAuthorName());
        assertFalse(firstItem.getIsLiked()); // 未登录为 false
    }

    @Test
    @DisplayName("测试最新信息流（次页带游标）：返回少于 size 条，hasMore=false 且 nextCursor=null")
    void testFeedNewestNextPage() {
        Instant now = Instant.now();
        String cursor = Base64.getUrlEncoder().encodeToString(("new:" + now.toEpochMilli() + ":10").getBytes(StandardCharsets.UTF_8));

        List<Article> mockList = new ArrayList<>();
        // 模拟最后一页只有 3 条
        for (long i = 11; i <= 13; i++) {
            mockList.add(createArticle(i, 100L, 1L, now.minusSeconds(i * 60), 100L, 20L));
        }

        when(articleMapper.selectFeedNewest(isNull(), isNull(), isNull(), any(Instant.class), eq(10L), eq(21)))
                .thenReturn(mockList);

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .cursor(cursor)
                .size(20)
                .sortBy(FeedSortType.NEWEST)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, null);

        assertNotNull(response);
        assertFalse(response.hasMore());
        assertNull(response.nextCursor());
        assertEquals(3, response.items().size());
    }

    @Test
    @DisplayName("测试综合推荐流（Redis 未命中降级为 MySQL）：使用 rec: 游标并生成推荐 nextCursor")
    void testFeedRecommendedFallbackToMysql() {
        Instant now = Instant.now();
        List<Article> mockList = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            mockList.add(createArticle(i, 200L, 2L, now.minusSeconds(i * 60), 500L - i * 10, 50L - i));
        }

        when(recommendRankService.getRankedArticleIds(isNull(), eq(21)))
                .thenReturn(Collections.emptyList());
        when(articleMapper.selectFeedRecommended(isNull(), isNull(), isNull(), any(Instant.class), isNull(), isNull(), eq(21)))
                .thenReturn(mockList);

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .size(20)
                .sortBy(FeedSortType.RECOMMENDED)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, null);

        assertNotNull(response);
        assertTrue(response.hasMore());
        assertNotNull(response.nextCursor());

        String decodedCursor = new String(Base64.getUrlDecoder().decode(response.nextCursor()), StandardCharsets.UTF_8);
        assertTrue(decodedCursor.startsWith("rec:"));
        verify(recommendRankService, times(1)).batchAddScores(eq(mockList));
    }

    @Test
    @DisplayName("测试综合推荐流（Redis ZSET 候选池命中）：直接从内存池拉取并按顺序装配")
    void testFeedRecommendedWithRedisHit() {
        Instant now = Instant.now();
        List<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> mockTuples = new ArrayList<>();
        List<Long> mockIds = new ArrayList<>();
        List<Article> mockArticles = new ArrayList<>();

        for (long i = 1; i <= 21; i++) {
            mockTuples.add(new org.springframework.data.redis.core.DefaultTypedTuple<>(String.valueOf(i), 1000.0 - i * 10));
            mockIds.add(i);
            mockArticles.add(createArticle(i, 200L, 2L, now.minusSeconds(i * 60), 500L - i * 10, 50L - i));
        }

        when(recommendRankService.getRankedArticleIds(isNull(), eq(21)))
                .thenReturn(mockTuples);
        when(articleMapper.selectByIds(eq(mockIds)))
                .thenReturn(mockArticles);
        when(recommendRankService.getScore(20L))
                .thenReturn(800.0);

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .size(20)
                .sortBy(FeedSortType.RECOMMENDED)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, null);

        assertNotNull(response);
        assertTrue(response.hasMore());
        assertNotNull(response.nextCursor());
        assertEquals(20, response.items().size());

        String decodedCursor = new String(Base64.getUrlDecoder().decode(response.nextCursor()), StandardCharsets.UTF_8);
        assertTrue(decodedCursor.startsWith("rec:"));
        assertTrue(decodedCursor.contains(":20")); // 第 20 条的文章 ID
    }


    @Test
    @DisplayName("测试登录用户点赞状态水合：Pipeline 批量判定 isLiked 为 true")
    void testDynamicHydrationWithLoggedInUser() {
        Instant now = Instant.now();
        List<Article> mockList = List.of(
                createArticle(101L, 300L, 1L, now, 10L, 5L),
                createArticle(102L, 300L, 1L, now, 20L, 8L)
        );

        when(articleMapper.selectFeedNewest(isNull(), isNull(), isNull(), isNull(), isNull(), eq(21)))
                .thenReturn(mockList);

        Long currentUserId = 888L;
        Map<String, Boolean> mockIsLikedMap = Map.of(
                "101", true,
                "102", false
        );
        when(counterService.batchIsSet(eq(CounterSchema.EntityType.ARTICLE), anyList(), eq(CounterSchema.ArticleMetric.LIKE), eq(currentUserId)))
                .thenReturn(mockIsLikedMap);

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .size(20)
                .sortBy(FeedSortType.NEWEST)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, currentUserId);

        assertNotNull(response);
        assertEquals(2, response.items().size());
        assertTrue(response.items().get(0).getIsLiked());
        assertFalse(response.items().get(1).getIsLiked());
    }

    @Test
    @DisplayName("测试相关推荐：正确查询当前文章分类与标签并返回 Top-5 卡片")
    void testListRelatedArticles() {
        Long articleId = 1L;
        Long authorId = 99L;
        Long categoryId = 2L;

        Article currentArticle = createArticle(articleId, authorId, categoryId, Instant.now(), 100L, 10L);
        when(articleMapper.selectById(articleId)).thenReturn(currentArticle);

        List<ArticleTagRel> rels = List.of(
                ArticleTagRel.builder().articleId(articleId).tagId(10L).build(),
                ArticleTagRel.builder().articleId(articleId).tagId(11L).build()
        );
        when(articleTagRelMapper.selectList(any())).thenReturn(rels);

        List<Article> relatedArticles = List.of(
                createArticle(2L, authorId, categoryId, Instant.now(), 50L, 5L),
                createArticle(3L, authorId, categoryId, Instant.now(), 40L, 4L)
        );
        when(articleMapper.selectRelatedArticles(eq(articleId), eq(categoryId), eq(List.of(10L, 11L)), eq(5)))
                .thenReturn(relatedArticles);

        List<ArticleFeedItemResponse> result = articleFeedService.listRelatedArticles(articleId, null);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).getId());
        assertEquals(3L, result.get(1).getId());
    }

    @Test
    @DisplayName("测试异常/非法游标容错：不抛异常，优雅降级为首屏检索")
    void testCorruptedCursorFallback() {
        when(articleMapper.selectFeedNewest(isNull(), isNull(), isNull(), isNull(), isNull(), eq(21)))
                .thenReturn(Collections.emptyList());

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .cursor("Illegal_Corrupted_Base64%%%")
                .size(20)
                .sortBy(FeedSortType.NEWEST)
                .build();

        assertDoesNotThrow(() -> {
            ArticleFeedPageResponse response = articleFeedService.getFeed(request, null);
            assertNotNull(response);
            assertFalse(response.hasMore());
        });

        // 验证降级后 cursorTime 和 cursorId 传入 null 从首屏查
        verify(articleMapper, times(1)).selectFeedNewest(isNull(), isNull(), isNull(), isNull(), isNull(), eq(21));
    }
}
