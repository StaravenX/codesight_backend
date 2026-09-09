package com.codesight.article;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.app.CodeSightApplication;
import com.codesight.article.api.dto.request.ArticleCreateRequest;
import com.codesight.article.api.dto.request.ArticleFeedRequest;
import com.codesight.article.api.dto.request.ArticlePatchRequest;
import com.codesight.article.api.dto.response.ArticleCreateResponse;
import com.codesight.article.api.dto.response.ArticleDetailResponse;
import com.codesight.article.api.dto.response.ArticleFeedPageResponse;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.mapper.ArticleTagRelMapper;
import com.codesight.article.model.dto.ArticleDetailStatic;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.entity.ArticleTagRel;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.FeedSortType;
import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.article.constant.FeedRedisKeys;
import com.codesight.article.service.ArticleCacheService;
import com.codesight.article.service.ArticleFeedService;
import com.codesight.article.service.ArticleService;
import com.codesight.article.service.RecommendRankService;
import com.codesight.common.exception.BusinessException;
import com.codesight.relation.service.RelationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文章模块真实中间件端到端集成测试（End-to-End Containerized IT）。
 * <p>
 * 依赖环境：真实 Docker 容器（MySQL 3306 + Redis 6379 + Kafka 9092）。
 * 覆盖场景：
 * 1.  MySQL 物理存盘、CommonMark AST 摘要与 TOC 提取、article_tag_rel 多对多关联维护；
 * 2. 发布与 Redis ZSET 候选池注入、增量推分、真实 Redis Lua 降温衰减脚本执行；
 * 3. 多级缓存（Caffeine L1 + Redis L2）穿透加载、回填与更新下架双淘汰；
 * 4. Keys-Seek / Keyset 游标分页流拉取与防重翻页；
 * 5. 社交关注流（Following Feed）推拉结合端到端全链路；
 * 6. 测试结束后自动清理 MySQL 与 Redis 残留数据
 */
@SpringBootTest(classes = CodeSightApplication.class)
public class ArticleIT {

    private static final String RECOMMEND_POOL_KEY = "feed:recommend:pool";
    private static final String DETAIL_CACHE_PREFIX = "article:detail:static:";

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private ArticleTagRelMapper articleTagRelMapper;

    @Autowired
    private ArticleCacheService articleCacheService;

    @Autowired
    private ArticleFeedService articleFeedService;

    @Autowired
    private RecommendRankService recommendRankService;

    @Autowired
    private RelationService relationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private com.codesight.user.UserMapper userMapper;

    private Long authorId;
    private final List<Long> createdArticleIds = new ArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        com.codesight.user.User testUser = com.codesight.user.User.builder()
                .nickname("IT测试创作者")
                .phone("138" + (System.currentTimeMillis() % 100000000L))
                .email("it_" + System.currentTimeMillis() + "@codesight.com")
                .csId("cs_" + System.currentTimeMillis())
                .build();
        userMapper.insert(testUser);
        this.authorId = testUser.getId();
    }

    @AfterEach
    void tearDown() {
        if (!createdArticleIds.isEmpty()) {
            for (Long articleId : createdArticleIds) {
                articleMapper.deleteById(articleId);
                articleTagRelMapper.delete(new LambdaQueryWrapper<ArticleTagRel>()
                        .eq(ArticleTagRel::getArticleId, articleId));
                redisTemplate.opsForZSet().remove(RECOMMEND_POOL_KEY, String.valueOf(articleId));
                redisTemplate.delete(DETAIL_CACHE_PREFIX + articleId);
            }
            createdArticleIds.clear();
        }
        if (authorId != null) {
            userMapper.deleteById(authorId);
        }
    }

    @Test
    @DisplayName("IT 场景 1：真实保存草稿 -> 验证 MySQL 物理落盘、CommonMark AST 解析及草稿隔离")
    void testSaveDraft_RealDatabasePersistenceAndAst() {
        ArticleCreateRequest draftReq = ArticleCreateRequest.builder()
                .title("【容器集成测试】Java 21 虚拟线程与协程调度实战（草稿）")
                .contentMd("# 深度剖析\n这是虚拟线程第一节。\n## 调度与载体线程\n这是第二节核心原理解析。")
                .coverUrl("https://cdn.codesight.com/covers/vthread.png")
                .categoryId(1L) // 对应种子数据：后端
                .tagIds(List.of(1L, 2L)) // 对应种子数据：Java, Spring Boot
                .isDraft(true)
                .build();

        // 1. 真实调用服务层接口
        ArticleCreateResponse response = articleService.createArticle(draftReq, authorId);
        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals(ArticleStatus.DRAFT, response.status());

        Long articleId = response.id();
        createdArticleIds.add(articleId);

        // 2. 真实从 MySQL 读取刚刚落库的实体记录
        Article saved = articleMapper.selectById(articleId);
        assertNotNull(saved, "MySQL 中必须真实持久化该文章主表记录");
        assertEquals("【容器集成测试】Java 21 虚拟线程与协程调度实战（草稿）", saved.getTitle());
        assertEquals(authorId, saved.getAuthorId());
        assertEquals(1L, saved.getCategoryId());
        assertEquals(ArticleStatus.DRAFT, saved.getStatus());
        assertNull(saved.getPublishTime(), "草稿状态发布时间必须为 null");

        // 验证 CommonMark AST 提炼出的摘要与 TOC 树
        assertNotNull(saved.getSummary());
        assertTrue(saved.getSummary().contains("虚拟线程第一节"));
        assertTrue(saved.getWordCount() > 0);
        assertNotNull(saved.getToc());
        assertFalse(saved.getToc().isEmpty());
        assertTrue(saved.getToc().stream().anyMatch(t -> t.getTitle().contains("调度与载体线程")));

        // 3. 真实查询关联表 article_tag_rel
        Long relCount = articleTagRelMapper.selectCount(new LambdaQueryWrapper<ArticleTagRel>()
                .eq(ArticleTagRel::getArticleId, articleId));
        assertEquals(2L, relCount, "article_tag_rel 表中必须真实持久化 2 条关联记录");

        // 4. 验证 Redis 推荐候选池
        Double rankScore = redisTemplate.opsForZSet().score(RECOMMEND_POOL_KEY, String.valueOf(articleId));
        assertNull(rankScore, "草稿绝不能被推入推荐池 ZSET 中");
    }

    @Test
    @DisplayName("IT 场景 2：直接公开发布 -> 验证推荐池 Redis ZSET 入池、推分与真实 Lua 脚本降温衰减")
    void testPublishAndRankDecay_RealRedisZSetAndLuaExecution() {
        ArticleCreateRequest publishReq = ArticleCreateRequest.builder()
                .title("【容器集成测试】现代推荐系统与向量化检索工程落地（公开发布）")
                .contentMd("# 推荐架构\nxx架构。\n## xx算法\nxxx。")
                .coverUrl("https://cdn.codesight.com/covers/rec.png")
                .categoryId(1L)
                .tagIds(List.of(1L))
                .isDraft(false) // 直接公开发布
                .build();

        ArticleCreateResponse createResp = articleService.createArticle(publishReq, authorId);
        Long articleId = createResp.id();
        createdArticleIds.add(articleId);

        // 1. 验证 MySQL 真实记录已发布
        Article saved = articleMapper.selectById(articleId);
        assertEquals(ArticleStatus.PUBLISHED, saved.getStatus());
        assertNotNull(saved.getPublishTime(), "公开发布文章必须有物理发布时间戳");

        // 2. 验证 Redis ZSET 候选池中已自动注入
        Double initScore = redisTemplate.opsForZSet().score(RECOMMEND_POOL_KEY, String.valueOf(articleId));
        assertNotNull(initScore, "公开发布后必须自动进入 feed:recommend:pool 候选池");
        assertEquals(10.0, initScore, 0.01, "初始底分必须为 10.0");

        // 3. 真实模拟用户互动推分（比如高频点赞 +20.0 分）
        recommendRankService.addOrIncrScore(articleId, 20.0);
        Double boostedScore = redisTemplate.opsForZSet().score(RECOMMEND_POOL_KEY, String.valueOf(articleId));
        assertNotNull(boostedScore);
        assertEquals(30.0, boostedScore, 0.01, "推分后推荐分应为 10.0 + 20.0 = 30.0");

        // 4. 真实在 Redis 引擎内执行 Lua 半衰期降温脚本（乘 0.9）
        recommendRankService.decayAll(0.9, 3000);

        Double decayedScore = redisTemplate.opsForZSet().score(RECOMMEND_POOL_KEY, String.valueOf(articleId));
        assertNotNull(decayedScore);
        // 30.0 * 0.9 = 27.0
        assertEquals(27.0, decayedScore, 0.1, "执行真实 Redis Lua 脚本后分数应乘以 0.9 衰减为 27.0 左右");
    }

    @Test
    @DisplayName("IT 场景 3：真实多级缓存穿透、回填与局部更新双淘汰（Cache Aside）")
    void testMultiLevelCache_RealCaffeineAndRedisDoubleEviction() {
        ArticleCreateRequest req = ArticleCreateRequest.builder()
                .title("【容器集成测试】多级缓存架构与防穿透实战")
                .contentMd("# 缓存设计\nL1 本地缓存与 L2 分布式缓存。")
                .categoryId(1L)
                .tagIds(List.of(1L))
                .isDraft(false)
                .build();

        ArticleCreateResponse createResp = articleService.createArticle(req, authorId);
        Long articleId = createResp.id();
        createdArticleIds.add(articleId);

        String redisDetailKey = DETAIL_CACHE_PREFIX + articleId;

        // 1. 首次查询：此时 Redis 应该尚未有该缓存
        Boolean hasCacheBefore = redisTemplate.hasKey(redisDetailKey);
        assertNotEquals(Boolean.TRUE, hasCacheBefore);

        // 2. 首次通过 CacheService 查询：触发真实 MySQL 回填与 Redis/Caffeine 写入
        ArticleDetailStatic staticDetail = articleCacheService.getStaticDetail(articleId);
        assertNotNull(staticDetail);
        assertEquals("【容器集成测试】多级缓存架构与防穿透实战", staticDetail.getTitle());

        // 3. 验证真实 Redis 中已生成该 Key
        Boolean hasCacheAfter = redisTemplate.hasKey(redisDetailKey);
        assertEquals(Boolean.TRUE, hasCacheAfter, "首次查询后 Redis 必须被真实回填");

        // 4. 真实更新文章标题，触发双淘汰
        ArticlePatchRequest patchReq = ArticlePatchRequest.builder()
                .title("【容器集成测试】多级缓存架构与防穿透实战（已局部修订）")
                .build();
        articleService.updateArticle(articleId, patchReq, authorId);

        // 5. 验证双淘汰：Redis 中的缓存 key 必须被真实 DEL
        Boolean hasCacheAfterUpdate = redisTemplate.hasKey(redisDetailKey);
        assertNotEquals(Boolean.TRUE, hasCacheAfterUpdate, "更新操作必须触发 Cache-Aside 淘汰，Redis Key 应被清除");

        // 6. 再次查询，验证能够重新从 MySQL 读出最新标题并完成再次回填
        ArticleDetailStatic reloaded = articleCacheService.getStaticDetail(articleId);
        assertNotNull(reloaded);
        assertEquals("【容器集成测试】多级缓存架构与防穿透实战（已局部修订）", reloaded.getTitle());
    }

    @Test
    @DisplayName("IT 场景 4：综合推荐流与详情聚合端到端联调（含虚拟线程并行组装）")
    void testFeedAndDetailAggregation_RealEndToEnd() {
        // 1. 创建一篇公开文章
        ArticleCreateRequest req = ArticleCreateRequest.builder()
                .title("【容器集成测试】Feed 流与虚拟线程聚合联调")
                .contentMd("# 综合推荐\n测试端到端游标流与虚拟线程并发详情组装。")
                .categoryId(1L)
                .tagIds(List.of(1L))
                .isDraft(false)
                .build();
        ArticleCreateResponse createResp = articleService.createArticle(req, authorId);
        Long articleId = createResp.id();
        createdArticleIds.add(articleId);

        // 2. 真实拉取详情页：验证虚拟线程池并行组装（静态详情 + 实时 16B SDS 计数 + 点赞位图）
        ArticleDetailResponse detail = articleService.getDetail(articleId, authorId);
        assertNotNull(detail);
        assertEquals("【容器集成测试】Feed 流与虚拟线程聚合联调", detail.getTitle());
        assertEquals(0L, detail.getViewCount());
        assertEquals(0L, detail.getLikeCount());
        assertFalse(detail.getIsLiked());

        // 3. 真实拉取推荐 Feed 流：优先从 Redis 候选池 Keys-Seek 拉取
        ArticleFeedRequest feedReq = ArticleFeedRequest.builder()
                .sortBy(FeedSortType.RECOMMENDED)
                .size(10)
                .build();

        ArticleFeedPageResponse feedPage = articleFeedService.getFeed(feedReq, authorId);
        assertNotNull(feedPage);
        assertNotNull(feedPage.items());
        assertFalse(feedPage.items().isEmpty(), "推荐流中必须能真实检索到刚刚公开发布并入池的文章");
        assertTrue(feedPage.items().stream().anyMatch(item -> item.getId().equals(articleId)),
                "推荐流 items 中必须包含新发布的测试文章 ID");
    }

    @Test
    @DisplayName("IT 场景 5：社交关注流端到端全链路（写扩散推入收件箱、发件箱物理留存与时间倒序拉取）")
    void testFollowingFeed_FullFlow() {
        // 1. 创建一位粉丝用户并关注创作者 authorId
        long seed = System.currentTimeMillis() % 10000000L;
        com.codesight.user.User fanUser = com.codesight.user.User.builder()
                .nickname("IT测试粉丝_" + seed)
                .phone("137" + String.format("%08d", seed))
                .email("fan_" + seed + "@codesight.com")
                .csId("cs_fan_" + seed)
                .build();
        userMapper.insert(fanUser);
        Long fanId = fanUser.getId();

        try {
            // 粉丝关注作者
            relationService.follow(fanId, authorId);

            // 2. 作者发布一篇公开文章（普通创作者，走写扩散）
            ArticleCreateRequest publishReq = ArticleCreateRequest.builder()
                    .title("【容器集成测试】关注流端到端推拉结合验证")
                    .contentMd("# 社交动态\n普通博主发文写扩散推入粉丝收件箱。")
                    .coverUrl("https://cdn.codesight.com/covers/feed.png")
                    .categoryId(1L)
                    .tagIds(List.of(1L))
                    .isDraft(false)
                    .build();

            ArticleCreateResponse createResp = articleService.createArticle(publishReq, authorId);
            Long articleId = createResp.id();
            createdArticleIds.add(articleId);

            // 3. 验证物理事实发件箱（Outbox）已写入
            Double outboxScore = redisTemplate.opsForZSet().score(FeedRedisKeys.getOutboxKey(authorId), String.valueOf(articleId));
            assertNotNull(outboxScore, "作者发件箱中必须物理留存该文章");

            // 4. 验证写扩散：粉丝个人收件箱（Inbox）已收到推送
            Double inboxScore = redisTemplate.opsForZSet().score(FeedRedisKeys.getInboxKey(fanId), String.valueOf(articleId));
            assertNotNull(inboxScore, "粉丝个人收件箱中必须已推入该文章");

            // 5. 粉丝端拉取关注流（GET /api/v1/articles/feed?sortBy=FOLLOWING）
            ArticleFeedRequest feedReq = ArticleFeedRequest.builder()
                    .sortBy(FeedSortType.FOLLOWING)
                    .size(10)
                    .build();

            ArticleFeedPageResponse feedPage = articleFeedService.getFeed(feedReq, fanId);
            assertNotNull(feedPage);
            assertNotNull(feedPage.items());
            assertFalse(feedPage.items().isEmpty(), "关注流中必须能够拉取到关注博主发布的文章");
            assertTrue(feedPage.items().stream().anyMatch(item -> item.getId().equals(articleId)),
                    "关注流结果集必须包含刚才发布的文章 ID");

            // 验证作者信息装配
            ArticleFeedItemResponse item = feedPage.items().stream()
                    .filter(it -> it.getId().equals(articleId))
                    .findFirst()
                    .orElseThrow();
            assertEquals(authorId, item.getAuthorId());
            assertEquals("【容器集成测试】关注流端到端推拉结合验证", item.getTitle());

            // 6. 异常场景保护：未登录拉取关注流必须抛出 BusinessException
            assertThrows(BusinessException.class, () -> articleFeedService.getFeed(feedReq, null));

        } finally {
            // 清理粉丝关系与收件箱
            try {
                relationService.unfollow(fanId, authorId);
            } catch (Exception ignored) {}
            redisTemplate.delete(FeedRedisKeys.getInboxKey(fanId));
            redisTemplate.delete(FeedRedisKeys.getOutboxKey(authorId));
            userMapper.deleteById(fanId);
        }
    }

    @Test
    @DisplayName("IT 场景 6：社交关注流推拉结合（Push-Pull Hybrid）与大 V 读扩散多路倒序归并")
    void testFollowingFeed_HybridPushPull_WithBigVReadDiffusion() throws InterruptedException {
        // 1. 创建一位大 V 博主与一位粉丝用户
        long seed = System.currentTimeMillis() % 10000000L;
        com.codesight.user.User bigVUser = com.codesight.user.User.builder()
                .nickname("IT测试大V_" + seed)
                .phone("139" + String.format("%08d", seed))
                .email("bigv_" + seed + "@codesight.com")
                .csId("cs_bigv_" + seed)
                .build();
        userMapper.insert(bigVUser);
        Long bigVId = bigVUser.getId();

        com.codesight.user.User fanUser = com.codesight.user.User.builder()
                .nickname("IT测试融合粉丝_" + seed)
                .phone("136" + String.format("%08d", seed))
                .email("fan2_" + seed + "@codesight.com")
                .csId("cs_fan2_" + seed)
                .build();
        userMapper.insert(fanUser);
        Long fanId = fanUser.getId();

        // 2. 将大 V 用户的粉丝计数写入 Redis 16B SDS 快照（设置为 6000，超过 5500 阈值）
        byte[] sdsBuffer = new byte[com.codesight.counter.schema.CounterSchema.TOTAL_BYTES];
        com.codesight.counter.schema.CounterSchema.writeInt32BE(
                sdsBuffer,
                com.codesight.counter.schema.CounterSchema.UserMetric.FOLLOWERS.offset(),
                6000L
        );
        String bigVSdsKey = com.codesight.counter.schema.CounterKeys.sdsKey(
                com.codesight.counter.schema.CounterSchema.EntityType.USER,
                String.valueOf(bigVId)
        );
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.stringCommands().set(bigVSdsKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), sdsBuffer);
            return null;
        });

        Long bigVArticleId = null;
        try {
            // 3. 粉丝同时关注普通博主 authorId 与大 V 博主 bigVId
            relationService.follow(fanId, authorId);
            relationService.follow(fanId, bigVId);

            // 4. 普通博主发布第 1 篇文章（时间早，走写扩散）
            ArticleCreateRequest normalReq = ArticleCreateRequest.builder()
                    .title("【容器集成测试】普通博主文章-推模式")
                    .contentMd("# 普通博主推文")
                    .categoryId(1L)
                    .tagIds(List.of(1L))
                    .isDraft(false)
                    .build();
            ArticleCreateResponse normalResp = articleService.createArticle(normalReq, authorId);
            Long normalArticleId = normalResp.id();
            createdArticleIds.add(normalArticleId);

            // 稍微休眠 20ms 以拉开时间戳差距
            Thread.sleep(20);

            // 5. 大 V 博主发布第 2 篇文章（时间晚，走读扩散）
            ArticleCreateRequest bigVReq = ArticleCreateRequest.builder()
                    .title("【容器集成测试】大V博主文章-拉模式")
                    .contentMd("# 大V博主拉文")
                    .categoryId(1L)
                    .tagIds(List.of(1L))
                    .isDraft(false)
                    .build();
            ArticleCreateResponse bigVResp = articleService.createArticle(bigVReq, bigVId);
            bigVArticleId = bigVResp.id();
            createdArticleIds.add(bigVArticleId);

            // 6. 验证推拉分流：
            // 6.1 普通博主文章推入了粉丝 Inbox
            Double normalInboxScore = redisTemplate.opsForZSet().score(FeedRedisKeys.getInboxKey(fanId), String.valueOf(normalArticleId));
            assertNotNull(normalInboxScore, "普通博主发文必须写扩散推入粉丝收件箱");

            // 6.2 大 V 文章仅入发件箱 Outbox，绝不推入粉丝 Inbox（化解写放大）
            Double bigVOutboxScore = redisTemplate.opsForZSet().score(FeedRedisKeys.getOutboxKey(bigVId), String.valueOf(bigVArticleId));
            assertNotNull(bigVOutboxScore, "大 V 发文必须物理保存在大 V 发件箱中");
            Double bigVInboxScore = redisTemplate.opsForZSet().score(FeedRedisKeys.getInboxKey(fanId), String.valueOf(bigVArticleId));
            assertNull(bigVInboxScore, "大 V 发文绝不能推入粉丝收件箱，避免写放大");

            // 7. 粉丝拉取关注流：系统触发 Inbox(推) + BigV Outbox(拉) 动态融合归并
            ArticleFeedRequest feedReq = ArticleFeedRequest.builder()
                    .sortBy(FeedSortType.FOLLOWING)
                    .size(10)
                    .build();
            ArticleFeedPageResponse feedPage = articleFeedService.getFeed(feedReq, fanId);

            assertNotNull(feedPage);
            assertNotNull(feedPage.items());
            assertTrue(feedPage.items().size() >= 2, "关注流应至少包含普通博主与大 V 博主各 1 篇文章");

            // 验证时间倒序：因为大 V 文章后发，其发布时间更新，必须排在第 1 位
            assertEquals(bigVArticleId, feedPage.items().get(0).getId(), "大 V 最新发布的文章应排在第 1 位");
            assertEquals(normalArticleId, feedPage.items().get(1).getId(), "普通博主先发布的文章应排在第 2 位");

        } finally {
            // 清理测试数据
            if (bigVArticleId != null) {
                articleTagRelMapper.delete(new LambdaQueryWrapper<ArticleTagRel>().eq(ArticleTagRel::getArticleId, bigVArticleId));
                articleMapper.deleteById(bigVArticleId);
                createdArticleIds.remove(bigVArticleId);
            }
            try {
                relationService.unfollow(fanId, authorId);
                relationService.unfollow(fanId, bigVId);
            } catch (Exception ignored) {}
            redisTemplate.delete(FeedRedisKeys.getInboxKey(fanId));
            redisTemplate.delete(FeedRedisKeys.getOutboxKey(bigVId));
            redisTemplate.delete(FeedRedisKeys.getOutboxKey(authorId));
            redisTemplate.delete(bigVSdsKey);
            redisTemplate.opsForSet().remove(FeedRedisKeys.BIG_V_SET_KEY, String.valueOf(bigVId));
            userMapper.deleteById(fanId);
            userMapper.deleteById(bigVId);
        }
    }
}
