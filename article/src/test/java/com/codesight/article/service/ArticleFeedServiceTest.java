package com.codesight.article.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codesight.article.api.dto.request.ArticleFeedRequest;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.article.api.dto.response.ArticleFeedPageResponse;
import com.codesight.article.config.FeedProperties;
import com.codesight.article.constant.FeedRedisKeys;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.mapper.ArticleTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import com.codesight.article.model.enums.FeedSortType;
import com.codesight.common.exception.BusinessException;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.relation.mapper.UserFollowerMapper;
import com.codesight.relation.model.UserFollower;
import com.codesight.relation.service.RelationCacheService;
import com.codesight.user.UserBaseInfo;
import com.codesight.user.UserCacheService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ArticleFeedService 信息流、社交关注流与相关推荐单元测试")
class ArticleFeedServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, UserFollower.class);
        TableInfoHelper.initTableInfo(assistant, Article.class);
    }

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleTagRelMapper articleTagRelMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private UserCacheService userCacheService;

    @Mock
    private CounterService counterService;

    @Mock
    private RecommendRankService recommendRankService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private UserFollowerMapper userFollowerMapper;

    @Mock
    private RelationCacheService relationCacheService;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ArticleRecommendVectorService articleRecommendVectorService;

    private final FeedProperties feedProperties = new FeedProperties();

    private ArticleFeedService articleFeedService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(zSetOperations.score(anyString(), anyString())).thenReturn(null);
        when(stringRedisTemplate.executePipelined(any(SessionCallback.class)))
                .thenAnswer(inv -> {
                    SessionCallback<?> callback = inv.getArgument(0);
                    List<Object> pipelineResults = new ArrayList<>();
                    RedisOperations mockOps = mock(RedisOperations.class);
                    ZSetOperations mockZSet = mock(ZSetOperations.class);
                    when(mockOps.opsForZSet()).thenReturn(mockZSet);
                    when(mockZSet.score(anyString(), anyString())).thenAnswer(scoreInv -> {
                        String key = scoreInv.getArgument(0);
                        String member = scoreInv.getArgument(1);
                        Double score = zSetOperations.score(key, member);
                        pipelineResults.add(score);
                        return score;
                    });
                    callback.execute(mockOps);
                    return pipelineResults;
                });
        when(articleRecommendVectorService.recommendAndRerank(any(), anyList()))
                .thenAnswer(inv -> inv.getArgument(1));

        articleFeedService = new ArticleFeedService(
                articleMapper,
                articleTagRelMapper,
                tagMapper,
                userCacheService,
                counterService,
                recommendRankService,
                stringRedisTemplate,
                userFollowerMapper,
                relationCacheService,
                articleRecommendVectorService,
                feedProperties
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

        UserBaseInfo author = new UserBaseInfo(100L, "极客作者", "https://example.com/a.png", null, null, null);
        when(userCacheService.batchGetUserBaseInfo(any())).thenReturn(Map.of(100L, author));

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

        when(recommendRankService.getRankedArticleIds(isNull(), isNull(), eq(21)))
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
        verify(recommendRankService, never()).batchAddScores(any());
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

        when(recommendRankService.getRankedArticleIds(isNull(), isNull(), eq(21)))
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
    @DisplayName("测试推荐流绝对分值游标漂移防御：已读曝光过滤与顺延补拉（Buffer Fetch）")
    void testFeedRecommendedWithExposedFilteringAndBufferFetch() {
        Long userId = 999L;
        Instant now = Instant.now();

        // 模拟推荐池单次 Buffer Fetch 一步到位拉取候选集（其中 1 和 3 已读曝光）
        List<TypedTuple<String>> tuples = List.of(
                new DefaultTypedTuple<>("1", 100.0),
                new DefaultTypedTuple<>("2", 90.0),
                new DefaultTypedTuple<>("3", 80.0),
                new DefaultTypedTuple<>("4", 70.0),
                new DefaultTypedTuple<>("5", 60.0)
        );

        when(recommendRankService.getRankedArticleIds(isNull(), isNull(), anyInt()))
                .thenReturn(tuples);

        // 模拟已读曝光判定：1 和 3 命中已读缓存，其余为 null
        String exposedKey = FeedRedisKeys.getExposedKey(userId);
        when(zSetOperations.score(eq(exposedKey), eq("1"))).thenReturn(1000.0);
        when(zSetOperations.score(eq(exposedKey), eq("3"))).thenReturn(1000.0);

        Article a2 = createArticle(2L, 200L, 1L, now, 100L, 10L);
        Article a4 = createArticle(4L, 200L, 1L, now, 90L, 9L);
        Article a5 = createArticle(5L, 200L, 1L, now, 80L, 8L);

        when(articleMapper.selectByIds(eq(List.of(2L, 4L, 5L))))
                .thenReturn(List.of(a2, a4, a5));

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .size(2)
                .sortBy(FeedSortType.RECOMMENDED)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, userId);

        assertNotNull(response);
        assertTrue(response.hasMore());
        assertEquals(2, response.items().size());

        // 验证已读文章 1 和 3 被剔除，顺延补拉出 2, 4
        assertEquals(2L, response.items().get(0).getId());
        assertEquals(4L, response.items().get(1).getId());

        // 验证 nextCursor 指向第 2 条文章 (4L)
        assertNotNull(response.nextCursor());
        String decodedCursor = new String(Base64.getUrlDecoder().decode(response.nextCursor()), StandardCharsets.UTF_8);
        assertTrue(decodedCursor.startsWith("rec:"));
        assertTrue(decodedCursor.contains(":4"));
    }

    @Test
    @DisplayName("测试推荐流 Buffer Fetch：过滤后数量恰好等于页大小但推荐池查满时，正确判定 hasMore 为 true")
    void testFeedRecommendedBufferFetch_ExactPageSizeWithMoreInPool() {
        Long userId = 999L;
        Instant now = Instant.now();

        // 构造满 fetchLimit(25) 的推荐池候选
        List<TypedTuple<String>> tuples = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            tuples.add(new DefaultTypedTuple<>(String.valueOf(i), 100.0 - i));
        }

        when(recommendRankService.getRankedArticleIds(isNull(), isNull(), anyInt()))
                .thenReturn(tuples);

        // 前 23 篇已读曝光，仅 24 和 25 未读
        String exposedKey = FeedRedisKeys.getExposedKey(userId);
        for (int i = 1; i <= 23; i++) {
            when(zSetOperations.score(eq(exposedKey), eq(String.valueOf(i)))).thenReturn(1000.0);
        }

        Article a24 = createArticle(24L, 200L, 1L, now, 100L, 10L);
        Article a25 = createArticle(25L, 200L, 1L, now, 90L, 9L);
        when(articleMapper.selectByIds(eq(List.of(24L, 25L)))).thenReturn(List.of(a24, a25));

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .size(2)
                .sortBy(FeedSortType.RECOMMENDED)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, userId);

        assertNotNull(response);
        assertEquals(2, response.items().size());
        // 虽然过滤后条数恰好为 2 (不大于 size)，但因为 Redis 查满了 fetchLimit，判定仍有下一页
        assertTrue(response.hasMore());
        assertNotNull(response.nextCursor());
        String decodedCursor = new String(Base64.getUrlDecoder().decode(response.nextCursor()), StandardCharsets.UTF_8);
        assertTrue(decodedCursor.contains(":25"));
    }

    @Test
    @DisplayName("测试推荐流极端场景：首轮缓冲因高曝光（重复20条）不足一页时，触发第二轮补拉凑满整页")
    void testFeedRecommendedBufferFetch_ExtremeExposureTriggersTopupToFillPage() {
        Long userId = 999L;
        Instant now = Instant.now();

        // 第 1 轮候选集 1~25 号
        List<TypedTuple<String>> round1 = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            round1.add(new DefaultTypedTuple<>(String.valueOf(i), 100.0 - i));
        }

        // 第 2 轮候选集 26~50 号
        List<TypedTuple<String>> round2 = new ArrayList<>();
        for (int i = 26; i <= 50; i++) {
            round2.add(new DefaultTypedTuple<>(String.valueOf(i), 70.0 - (i - 25)));
        }

        when(recommendRankService.getRankedArticleIds(isNull(), isNull(), anyInt()))
                .thenReturn(round1);
        when(recommendRankService.getRankedArticleIds(eq(75.0), eq(25L), anyInt()))
                .thenReturn(round2);

        // 模拟高密度曝光：第 1 轮 1~20 号（共 20 篇）全部曝光，21~25（仅 5 篇）未曝光
        String exposedKey = FeedRedisKeys.getExposedKey(userId);
        for (int i = 1; i <= 20; i++) {
            when(zSetOperations.score(eq(exposedKey), eq(String.valueOf(i)))).thenReturn(1000.0);
        }

        // mock 查询数据库：为两轮未曝光文章构造实体
        List<Article> articles = new ArrayList<>();
        for (long id = 21; id <= 50; id++) {
            articles.add(createArticle(id, 200L, 1L, now, 100L - id, 10L));
        }
        when(articleMapper.selectByIds(anyList())).thenReturn(articles);

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .size(10)
                .sortBy(FeedSortType.RECOMMENDED)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, userId);

        assertNotNull(response);
        // 验证通过第二轮补拉，成功凑满了请求的整页 10 条数据，杜绝了残缺半页
        assertEquals(10, response.items().size());
        assertTrue(response.hasMore());
        assertEquals(21L, response.items().get(0).getId());
        assertEquals(30L, response.items().get(9).getId());

        // 验证确实发起了第二轮受控补拉
        verify(recommendRankService, times(1)).getRankedArticleIds(eq(75.0), eq(25L), anyInt());
    }

    @Test
    @DisplayName("测试未登录用户跳过推荐流已读曝光过滤")
    void testFeedRecommendedAnonymousSkipsExposedFiltering() {
        Instant now = Instant.now();
        List<TypedTuple<String>> tuples = List.of(
                new DefaultTypedTuple<>("10", 100.0),
                new DefaultTypedTuple<>("20", 90.0)
        );

        when(recommendRankService.getRankedArticleIds(isNull(), isNull(), eq(2)))
                .thenReturn(tuples);

        Article a10 = createArticle(10L, 200L, 1L, now, 100L, 10L);
        Article a20 = createArticle(20L, 200L, 1L, now, 90L, 9L);
        when(articleMapper.selectByIds(List.of(10L, 20L))).thenReturn(List.of(a10, a20));

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .size(1)
                .sortBy(FeedSortType.RECOMMENDED)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, null);

        assertNotNull(response);
        assertTrue(response.hasMore());
        assertEquals(1, response.items().size());
        assertEquals(10L, response.items().getFirst().getId());
        verify(zSetOperations, never()).score(anyString(), anyString());
    }

    @Test
    @DisplayName("测试推荐池文章全部已读后自动兜底至 MySQL 推荐流并过滤曝光")
    void testFeedRecommendedAllExposedFallbackToMysql() {
        Long userId = 999L;
        Instant now = Instant.now();

        // 推荐池仅 1 篇文章且已被曝光
        List<TypedTuple<String>> poolTuples = List.of(
                new DefaultTypedTuple<>("100", 50.0)
        );
        when(recommendRankService.getRankedArticleIds(isNull(), isNull(), anyInt()))
                .thenReturn(poolTuples);

        String exposedKey = FeedRedisKeys.getExposedKey(userId);
        when(zSetOperations.score(eq(exposedKey), eq("100"))).thenReturn(500.0);

        // 兜底 MySQL 返回未曝光文章
        Article mysqlArticle = createArticle(200L, 300L, 1L, now, 60L, 5L);
        when(articleMapper.selectFeedRecommended(isNull(), isNull(), isNull(), any(Instant.class), isNull(), isNull(), eq(2)))
                .thenReturn(List.of(mysqlArticle));

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .size(1)
                .sortBy(FeedSortType.RECOMMENDED)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, userId);

        assertNotNull(response);
        assertEquals(1, response.items().size());
        assertEquals(200L, response.items().getFirst().getId());
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

    @Test
    @DisplayName("普通博主发文：全量写入个人发件箱，并异步推入粉丝收件箱（写扩散）")
    void testPublish_NormalAuthor_WritesOutboxAndPushesToFollowers() {
        Long authorId = 1001L;
        Long articleId = 8888L;
        Instant now = Instant.now();

        Article article = Article.builder()
                .id(articleId)
                .authorId(authorId)
                .status(ArticleStatus.PUBLISHED)
                .visible(ArticleVisible.PUBLIC)
                .publishTime(now)
                .build();

        // 模拟博主粉丝数为 500（< 5000，普通博主）
        when(counterService.getCounts(CounterSchema.EntityType.USER, String.valueOf(authorId)))
                .thenReturn(Map.of(CounterSchema.UserMetric.FOLLOWERS, 500L));

        // 模拟博主的粉丝列表为 [2001L, 2002L]
        UserFollower f1 = UserFollower.builder().fromUserId(2001L).toUserId(authorId).build();
        UserFollower f2 = UserFollower.builder().fromUserId(2002L).toUserId(authorId).build();
        when(userFollowerMapper.selectList(any())).thenReturn(List.of(f1, f2));

        articleFeedService.onArticlePublished(article);

        // 验证 1：无条件写入发件箱 feed:outbox:1001 并截断
        verify(zSetOperations).add(eq(FeedRedisKeys.getOutboxKey(authorId)), eq(String.valueOf(articleId)), eq((double) now.toEpochMilli()));
        verify(zSetOperations).removeRange(eq(FeedRedisKeys.getOutboxKey(authorId)), eq(0L), eq(-(feedProperties.getOutboxMaxCapacity() + 1L)));

        // 验证 2：触发了粉丝查询与 Pipeline 推送
        verify(userFollowerMapper).selectList(any());
        verify(stringRedisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("明星大 V 发文：仅写入个人发件箱，跳过粉丝收件箱推送（读扩散）")
    void testPublish_BigVAuthor_OnlyWritesOutbox() {
        Long bigVId = 9999L;
        Long articleId = 7777L;
        Instant now = Instant.now();

        Article article = Article.builder()
                .id(articleId)
                .authorId(bigVId)
                .status(ArticleStatus.PUBLISHED)
                .visible(ArticleVisible.PUBLIC)
                .publishTime(now)
                .build();

        // 模拟大 V 粉丝数为 10,000（>= 5000）
        when(counterService.getCounts(CounterSchema.EntityType.USER, String.valueOf(bigVId)))
                .thenReturn(Map.of(CounterSchema.UserMetric.FOLLOWERS, 10000L));

        articleFeedService.onArticlePublished(article);

        // 验证 1：无条件写入大 V 个人发件箱
        verify(zSetOperations).add(eq(FeedRedisKeys.getOutboxKey(bigVId)), eq(String.valueOf(articleId)), eq((double) now.toEpochMilli()));

        // 验证 2：绝不查询粉丝列表，绝不推收件箱（化解写放大）
        verify(userFollowerMapper, never()).selectList(any());
        verify(stringRedisTemplate, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("未登录查看关注流：抛出 UNAUTHORIZED 业务异常")
    void testGetFollowingFeed_Unauthenticated() {
        ArticleFeedRequest req = ArticleFeedRequest.builder().size(20).build();
        assertThatThrownBy(() -> articleFeedService.getFollowingFeed(req, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("无任何关注博主：直接返回空列表")
    void testGetFollowingFeed_NoFollowings_ReturnsEmpty() {
        Long currentUserId = 12345L;
        when(relationCacheService.getFollowingUserIds(currentUserId))
                .thenReturn(Collections.emptySet());

        ArticleFeedRequest req = ArticleFeedRequest.builder().size(20).build();
        ArticleFeedPageResponse response = articleFeedService.getFollowingFeed(req, currentUserId);

        assertThat(response).isNotNull();
        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("推拉融合归并：多路时间倒序排列与普通人升大 V 后的内存去重")
    void testGetFollowingFeed_HybridMerge_OrderAndDedup() {
        Long currentUserId = 1000L;
        Long normalAuthorId = 2001L;
        Long bigVAuthorId = 3001L;

        when(relationCacheService.getFollowingUserIds(currentUserId))
                .thenReturn(Set.of(normalAuthorId, bigVAuthorId));

        when(counterService.batchGetCounts(eq(CounterSchema.EntityType.USER), anyList()))
                .thenReturn(Map.of(
                        String.valueOf(normalAuthorId), Map.of(CounterSchema.UserMetric.FOLLOWERS, 100L),
                        String.valueOf(bigVAuthorId), Map.of(CounterSchema.UserMetric.FOLLOWERS, 8000L)
                ));

        // 3. 模拟多路 Redis 数据：
        Set<TypedTuple<String>> inboxTuples = new LinkedHashSet<>();
        inboxTuples.add(new DefaultTypedTuple<>("201", 3000.0));
        inboxTuples.add(new DefaultTypedTuple<>("101", 1000.0));

        Set<TypedTuple<String>> bigVTuples = new LinkedHashSet<>();
        bigVTuples.add(new DefaultTypedTuple<>("202", 5000.0));
        bigVTuples.add(new DefaultTypedTuple<>("201", 3000.0));

        when(stringRedisTemplate.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(inboxTuples, bigVTuples));

        Article a202 = Article.builder().id(202L).authorId(bigVAuthorId).status(ArticleStatus.PUBLISHED).visible(ArticleVisible.PUBLIC).build();
        Article a201 = Article.builder().id(201L).authorId(bigVAuthorId).status(ArticleStatus.PUBLISHED).visible(ArticleVisible.PUBLIC).build();
        Article a101 = Article.builder().id(101L).authorId(normalAuthorId).status(ArticleStatus.PUBLISHED).visible(ArticleVisible.PUBLIC).build();
        when(articleMapper.selectByIds(anyList())).thenReturn(List.of(a202, a201, a101));
        when(articleMapper.selectByIds(anyList())).thenReturn(List.of(a202, a201, a101));

        ArticleFeedRequest req = ArticleFeedRequest.builder().size(2).build();
        ArticleFeedPageResponse response = articleFeedService.getFollowingFeed(req, currentUserId);

        assertThat(response).isNotNull();
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextCursor()).isEqualTo("3000"); // 前两篇为 202（5000ms）和 201（3000ms）
        List<Long> resultIds = response.items().stream().map(ArticleFeedItemResponse::getId).toList();
        assertThat(resultIds).containsExactly(202L, 201L);
    }

    @Test
    @DisplayName("文章下架或删除：从发件箱移除")
    void testOnArticleRemoved() {
        Article article = Article.builder().id(555L).authorId(1001L).build();
        articleFeedService.onArticleRemoved(article);

        verify(zSetOperations).remove(eq(FeedRedisKeys.getOutboxKey(1001L)), eq("555"));
    }

    @Test
    @DisplayName("通用信息流 getFeed 传入 FOLLOWING 时自动路由到关注流")
    void testGetFeed_RoutesToFollowingFeed() {
        Long currentUserId = 12345L;
        when(relationCacheService.getFollowingUserIds(currentUserId))
                .thenReturn(Collections.emptySet());

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .sortBy(FeedSortType.FOLLOWING)
                .size(20)
                .build();

        ArticleFeedPageResponse response = articleFeedService.getFeed(request, currentUserId);

        assertThat(response).isNotNull();
        assertThat(response.items()).isEmpty();
        verify(relationCacheService).getFollowingUserIds(currentUserId);
    }

    @Test
    @DisplayName("大 V 状态机判定全场景覆盖")
    void testIsBigV_HysteresisBand() {
        Long authorId = 9999L;
        String authorStr = String.valueOf(authorId);

        boolean promoted = articleFeedService.isBigV(authorId, 5500L);
        assertThat(promoted).isTrue();
        verify(setOperations).add(FeedRedisKeys.BIG_V_SET_KEY, authorStr);

        boolean demoted = articleFeedService.isBigV(authorId, 4499L);
        assertThat(demoted).isFalse();
        verify(setOperations).remove(FeedRedisKeys.BIG_V_SET_KEY, authorStr);

        when(setOperations.isMember(FeedRedisKeys.BIG_V_SET_KEY, authorStr)).thenReturn(true);
        boolean keepBigV = articleFeedService.isBigV(authorId, 5000L);
        assertThat(keepBigV).isTrue();

        when(setOperations.isMember(FeedRedisKeys.BIG_V_SET_KEY, authorStr)).thenReturn(false);
        boolean keepNormal = articleFeedService.isBigV(authorId, 5000L);
        assertThat(keepNormal).isFalse();
    }

    @Test
    @DisplayName("大 V 跌落时从发件箱回填粉丝收件箱并维护容量")
    void testBackfillOnDemotion_Success() {
        Long authorId = 8888L;
        Set<TypedTuple<String>> outboxTuples = new LinkedHashSet<>();
        outboxTuples.add(new DefaultTypedTuple<>("101", 1000.0));
        outboxTuples.add(new DefaultTypedTuple<>("102", 2000.0));

        when(zSetOperations.reverseRangeWithScores(eq(FeedRedisKeys.getOutboxKey(authorId)), eq(0L), eq(19L)))
                .thenReturn(outboxTuples);

        UserFollower f1 = UserFollower.builder().fromUserId(2001L).toUserId(authorId).build();
        UserFollower f2 = UserFollower.builder().fromUserId(2002L).toUserId(authorId).build();
        when(userFollowerMapper.selectList(any())).thenReturn(List.of(f1, f2));

        articleFeedService.backfillOnDemotion(authorId);

        // 验证执行了 Pipeline 推送
        verify(stringRedisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("空作者或空发件箱边界防御")
    void testBackfillOnDemotion_EmptyGuards() {
        // 1. null authorId
        assertDoesNotThrow(() -> articleFeedService.backfillOnDemotion(null));

        // 2. 发件箱为空
        when(zSetOperations.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Collections.emptySet());
        articleFeedService.backfillOnDemotion(9999L);
        verify(userFollowerMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("全站推荐流接入双向向量感知：精排调换文章顺序并生效")
    void testRecommendedFeed_WithVectorRerankApplied() {
        Article a1 = createArticle(101L, 1L, 1L, Instant.now(), 100L, 10L);
        Article a2 = createArticle(102L, 2L, 1L, Instant.now(), 200L, 20L);

        List<TypedTuple<String>> tuples = List.of(
                new DefaultTypedTuple<>("101", 1000.0),
                new DefaultTypedTuple<>("102", 900.0)
        );
        when(recommendRankService.getRankedArticleIds(isNull(), isNull(), anyInt())).thenReturn(tuples);
        when(articleMapper.selectByIds(List.of(101L, 102L))).thenReturn(List.of(a1, a2));
 
        // 模拟 AI 模块精排：用户画像与 a2 契合度极高，重排为 [a2, a1]
        Long currentUserId = 888L;
        when(articleRecommendVectorService.recommendAndRerank(eq(currentUserId), anyList()))
                .thenReturn(List.of(a2, a1));

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .sortBy(FeedSortType.RECOMMENDED)
                .size(10)
                .build();
        ArticleFeedPageResponse response = articleFeedService.getFeed(request, currentUserId);

        assertNotNull(response);
        assertEquals(2, response.items().size());
        assertEquals(102L, response.items().get(0).getId()); // a2 跃升至第一位
        assertEquals(101L, response.items().get(1).getId());
    }

    @Test
    @DisplayName("全站推荐流接入双向向量感知：负向语义剪枝剔除同质营销软文")
    void testRecommendedFeed_WithSemanticPruningApplied() {
        Article cleanArticle = createArticle(201L, 1L, 1L, Instant.now(), 100L, 10L);
        Article softArticle = createArticle(202L, 2L, 1L, Instant.now(), 200L, 20L);

        List<TypedTuple<String>> tuples = List.of(
                new DefaultTypedTuple<>("201", 1000.0),
                new DefaultTypedTuple<>("202", 950.0)
        );
        when(recommendRankService.getRankedArticleIds(isNull(), isNull(), anyInt())).thenReturn(tuples);
        when(articleMapper.selectByIds(List.of(201L, 202L))).thenReturn(List.of(cleanArticle, softArticle));

        // 模拟 AI 模块负向语义剪枝：剔除 202 同质营销软文，仅保留 201
        Long currentUserId = 888L;
        when(articleRecommendVectorService.recommendAndRerank(eq(currentUserId), anyList()))
                .thenReturn(List.of(cleanArticle));

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .sortBy(FeedSortType.RECOMMENDED)
                .size(10)
                .build();
        ArticleFeedPageResponse response = articleFeedService.getFeed(request, currentUserId);

        assertNotNull(response);
        assertEquals(1, response.items().size());
        assertEquals(201L, response.items().getFirst().getId());
    }

    @Test
    @DisplayName("推荐流翻页：正确解析游标中的 score 与 articleId 并传递给排位服务")
    void testRecommendedFeed_CursorPassesArticleIdToRankService() {
        // 构建游标：rec:80:1002 (prefix=rec, score=80, articleId=1002)
        String raw = "rec:80:1002";
        String fullCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        when(recommendRankService.getRankedArticleIds(eq(80.0), eq(1002L), eq(11)))
                .thenReturn(Collections.emptyList());

        ArticleFeedRequest request = ArticleFeedRequest.builder()
                .sortBy(FeedSortType.RECOMMENDED)
                .cursor(fullCursor)
                .size(10)
                .build();

        articleFeedService.getFeed(request, null);

        verify(recommendRankService, times(1)).getRankedArticleIds(eq(80.0), eq(1002L), eq(11));
    }

    @Test
    @DisplayName("新关注普通博主：成功从发件箱回填历史文章至粉丝收件箱")
    void testBackfillOnFollow_NormalBlogger_ShouldBackfillInbox() {
        Long authorId = 8888L;
        Long followerId = 2001L;

        when(counterService.getCounts(eq(CounterSchema.EntityType.USER), eq(String.valueOf(authorId))))
                .thenReturn(Map.of(CounterSchema.UserMetric.FOLLOWERS, 100L));

        Set<TypedTuple<String>> outboxTuples = new LinkedHashSet<>();
        outboxTuples.add(new DefaultTypedTuple<>("101", 1000.0));
        outboxTuples.add(new DefaultTypedTuple<>("102", 2000.0));

        when(zSetOperations.reverseRangeWithScores(eq(FeedRedisKeys.getOutboxKey(authorId)), eq(0L), eq(19L)))
                .thenReturn(outboxTuples);

        articleFeedService.backfillOnFollow(followerId, authorId);

        verify(stringRedisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("新关注大 V 博主：跳过收件箱回填（大 V 走读扩散拉模式）")
    void testBackfillOnFollow_BigV_ShouldSkip() {
        Long authorId = 9999L;
        Long followerId = 2001L;

        when(counterService.getCounts(eq(CounterSchema.EntityType.USER), eq(String.valueOf(authorId))))
                .thenReturn(Map.of(CounterSchema.UserMetric.FOLLOWERS, 6000L));

        articleFeedService.backfillOnFollow(followerId, authorId);

        verify(stringRedisTemplate, never()).executePipelined(any(SessionCallback.class));
        verify(zSetOperations, never()).reverseRangeWithScores(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("新关注普通博主发件箱为空时，兜底从 MySQL 捞取公开文章回填")
    void testBackfillOnFollow_EmptyOutbox_ShouldFallbackDB() {
        Long authorId = 8888L;
        Long followerId = 2001L;

        when(counterService.getCounts(eq(CounterSchema.EntityType.USER), eq(String.valueOf(authorId))))
                .thenReturn(Map.of(CounterSchema.UserMetric.FOLLOWERS, 100L));
        when(zSetOperations.reverseRangeWithScores(eq(FeedRedisKeys.getOutboxKey(authorId)), eq(0L), eq(19L)))
                .thenReturn(Collections.emptySet());

        Article a1 = Article.builder().id(101L).authorId(authorId).publishTime(Instant.now()).status(ArticleStatus.PUBLISHED).visible(ArticleVisible.PUBLIC).build();
        when(articleMapper.selectList(any())).thenReturn(List.of(a1));

        articleFeedService.backfillOnFollow(followerId, authorId);

        verify(articleMapper).selectList(any());
        verify(stringRedisTemplate).executePipelined(any(SessionCallback.class));
    }

    @Test
    @DisplayName("取关普通博主：从粉丝收件箱批量移除该博主所有文章")
    void testCleanupOnUnfollow_ShouldRemoveFromInbox() {
        Long authorId = 8888L;
        Long followerId = 2001L;

        when(zSetOperations.range(eq(FeedRedisKeys.getOutboxKey(authorId)), eq(0L), eq(-1L)))
                .thenReturn(Set.of("101", "102"));

        articleFeedService.cleanupOnUnfollow(followerId, authorId);

        verify(zSetOperations).remove(eq(FeedRedisKeys.getInboxKey(followerId)), any(Object[].class));
    }

    @Test
    @DisplayName("取关普通博主发件箱为空时，兜底从数据库获取文章 ID 并清理")
    void testCleanupOnUnfollow_EmptyOutbox_ShouldFallbackDB() {
        Long authorId = 8888L;
        Long followerId = 2001L;

        when(zSetOperations.range(eq(FeedRedisKeys.getOutboxKey(authorId)), eq(0L), eq(-1L)))
                .thenReturn(Collections.emptySet());

        Article a1 = Article.builder().id(101L).build();
        when(articleMapper.selectList(any())).thenReturn(List.of(a1));

        articleFeedService.cleanupOnUnfollow(followerId, authorId);

        verify(articleMapper).selectList(any());
        verify(zSetOperations).remove(eq(FeedRedisKeys.getInboxKey(followerId)), any(Object[].class));
    }

    @Test
    @DisplayName("关注回填与取关清理空入参安全防御")
    void testFollowBackfillAndCleanup_NullGuards() {
        assertDoesNotThrow(() -> articleFeedService.backfillOnFollow(null, null));
        assertDoesNotThrow(() -> articleFeedService.backfillOnFollow(1L, null));
        assertDoesNotThrow(() -> articleFeedService.cleanupOnUnfollow(null, null));
        assertDoesNotThrow(() -> articleFeedService.cleanupOnUnfollow(1L, null));
    }
}

