package com.codesight.counter.service;

import com.codesight.counter.event.CounterEvent;
import com.codesight.counter.event.CounterEventProducer;
import com.codesight.counter.schema.CounterRebuilder;
import com.codesight.counter.schema.CounterSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CounterService 核心计数服务单元测试")
class CounterServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisScript<Long> toggleBitScript;

    @Mock
    private CounterEventProducer eventProducer;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock lock;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private CounterRebuilder mockArticleRebuilder;

    private CounterService counterService;

    @BeforeEach
    void setUp() {
        lenient().when(mockArticleRebuilder.entityType()).thenReturn(CounterSchema.EntityType.ARTICLE);

        List<CounterRebuilder> rebuilders = Collections.singletonList(mockArticleRebuilder);
        counterService = new CounterService(
                stringRedisTemplate,
                toggleBitScript,
                eventProducer,
                redisson,
                rebuilders
        );
    }

    @Test
    @DisplayName("测试 toggle：状态真实改变（返回 1）触发 Kafka 事件投递")
    void testToggleStateChanged() {
        when(stringRedisTemplate.execute(
                eq(toggleBitScript),
                anyList(),
                eq("10"),
                eq("add")
        )).thenReturn(1L);

        boolean result = counterService.toggle(CounterSchema.EntityType.ARTICLE, "1001", CounterSchema.ArticleMetric.LIKE, 10L, true);

        assertTrue(result);
        ArgumentCaptor<CounterEvent> captor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(eventProducer, times(1)).publish(captor.capture());

        CounterEvent event = captor.getValue();
        assertEquals(CounterSchema.EntityType.ARTICLE, event.entityType());
        assertEquals("1001", event.entityId());
        assertEquals("like", event.metric());
        assertEquals(1, event.idx());
        assertEquals(10L, event.userId());
        assertEquals(1, event.delta());
    }

    @Test
    @DisplayName("测试 toggle：幂等拦截（返回 0）不投递 Kafka 事件")
    void testToggleStateNotChanged() {
        when(stringRedisTemplate.execute(
                eq(toggleBitScript),
                anyList(),
                eq("10"),
                eq("add")
        )).thenReturn(0L);

        boolean result = counterService.toggle(CounterSchema.EntityType.ARTICLE, "1001", CounterSchema.ArticleMetric.LIKE, 10L, true);

        assertFalse(result);
        verify(eventProducer, never()).publish(any());
    }

    @Test
    @DisplayName("测试 isSet：查询单点位图激活状态")
    void testIsSet() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getBit(anyString(), eq(15L))).thenReturn(true);

        boolean isLiked = counterService.isSet(CounterSchema.EntityType.ARTICLE, "1001", CounterSchema.ArticleMetric.LIKE, 15L);
        assertTrue(isLiked);
    }

    @Test
    @DisplayName("测试 batchIsSet：管道化批量查询多实体激活状态")
    void testBatchIsSet() {
        when(stringRedisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(Arrays.asList(true, false));

        Map<String, Boolean> res = counterService.batchIsSet(
                CounterSchema.EntityType.ARTICLE,
                Arrays.asList("1001", "1002"),
                CounterSchema.ArticleMetric.LIKE,
                15L
        );

        assertEquals(2, res.size());
        assertTrue(res.get("1001"));
        assertFalse(res.get("1002"));
    }

    @Test
    @DisplayName("测试 increase：纯标量增量投递与 delta=0 忽略")
    void testIncrease() {
        // 增量正常投递
        counterService.increase(CounterSchema.EntityType.ARTICLE, "1001", CounterSchema.ArticleMetric.VIEWS, 999L, 1);
        verify(eventProducer, times(1)).publish(any(CounterEvent.class));

        // delta=0 忽略
        counterService.increase(CounterSchema.EntityType.ARTICLE, "1001", CounterSchema.ArticleMetric.VIEWS, 999L, 0);
        verify(eventProducer, times(1)).publish(any(CounterEvent.class)); // 仍然只有 1 次
    }

    @Test
    @DisplayName("测试 getCounts：缓存命中直接读取 16B SDS，不触发重建")
    void testGetCountsCacheHit() {
        byte[] raw = new byte[CounterSchema.TOTAL_BYTES];
        CounterSchema.writeInt32BE(raw, CounterSchema.ArticleMetric.VIEWS.offset(), 500L);
        CounterSchema.writeInt32BE(raw, CounterSchema.ArticleMetric.LIKE.offset(), 30L);
        CounterSchema.writeInt32BE(raw, CounterSchema.ArticleMetric.COMMENT.offset(), 5L);
        CounterSchema.writeInt32BE(raw, CounterSchema.ArticleMetric.FAVORITE.offset(), 12L);

        when(stringRedisTemplate.execute(any(RedisCallback.class))).thenReturn(raw);

        Map<CounterSchema.MetricItem, Long> counts = counterService.getCounts(CounterSchema.EntityType.ARTICLE, "1001");

        assertNotNull(counts);
        assertEquals(500L, counts.get(CounterSchema.ArticleMetric.VIEWS));
        assertEquals(30L, counts.get(CounterSchema.ArticleMetric.LIKE));
        assertEquals(5L, counts.get(CounterSchema.ArticleMetric.COMMENT));
        assertEquals(12L, counts.get(CounterSchema.ArticleMetric.FAVORITE));

        verify(redisson, never()).getLock(anyString());
    }

    @Test
    @DisplayName("测试 getCounts：缓存未命中触发 SPI 策略自愈重建流程")
    void testGetCountsCacheMissTriggersRebuild() throws InterruptedException {
        // 1. 模拟初次读取 getRawBytes 为 null
        when(stringRedisTemplate.execute(any(RedisCallback.class))).thenReturn(null);

        // 2. 模拟分布式锁
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        // 3. 模拟业务策略重建真值
        Map<CounterSchema.MetricItem, Long> rebuilderResult = new HashMap<>();
        rebuilderResult.put(CounterSchema.ArticleMetric.VIEWS, 10000L);
        rebuilderResult.put(CounterSchema.ArticleMetric.LIKE, 800L);
        rebuilderResult.put(CounterSchema.ArticleMetric.COMMENT, 50L);
        rebuilderResult.put(CounterSchema.ArticleMetric.FAVORITE, 200L);
        when(mockArticleRebuilder.rebuild("1001")).thenReturn(rebuilderResult);

        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);

        Map<CounterSchema.MetricItem, Long> counts = counterService.getCounts(CounterSchema.EntityType.ARTICLE, "1001");

        assertNotNull(counts);
        assertEquals(10000L, counts.get(CounterSchema.ArticleMetric.VIEWS));
        assertEquals(800L, counts.get(CounterSchema.ArticleMetric.LIKE));
        assertEquals(50L, counts.get(CounterSchema.ArticleMetric.COMMENT));
        assertEquals(200L, counts.get(CounterSchema.ArticleMetric.FAVORITE));

        // 验证调用了策略与锁释放
        verify(mockArticleRebuilder, times(1)).rebuild("1001");
        verify(lock, times(1)).unlock();
    }

    @Test
    @DisplayName("测试 bitCountShards：无分片时返回 -1（代表 Redis 位图缺失）")
    void testBitCountShardsEmpty() {
        when(stringRedisTemplate.keys(anyString())).thenReturn(Collections.emptySet());

        long count = counterService.bitCountShards(CounterSchema.EntityType.ARTICLE, "1001", CounterSchema.ArticleMetric.LIKE);
        assertEquals(-1L, count);
    }

    @Test
    @DisplayName("测试 increaseView：已登录用户首次访问（放行并异步累加 PV）")
    void testIncreaseViewUserFirstVisit() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("pv:dedup:article:1001:u:888"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        counterService.increaseView(CounterSchema.EntityType.ARTICLE, "1001", 888L, "127.0.0.1");

        ArgumentCaptor<CounterEvent> captor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(eventProducer, times(1)).publish(captor.capture());

        CounterEvent event = captor.getValue();
        assertEquals(CounterSchema.EntityType.ARTICLE, event.entityType());
        assertEquals("1001", event.entityId());
        assertEquals("views", event.metric());
        assertEquals(888L, event.userId());
        assertEquals(1, event.delta());
    }

    @Test
    @DisplayName("测试 increaseView：未登录游客首次访问（基于 IP 放行并异步累加 PV）")
    void testIncreaseViewAnonymousFirstVisit() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("pv:dedup:article:1001:ip:192.168.1.100"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        counterService.increaseView(CounterSchema.EntityType.ARTICLE, "1001", null, "192.168.1.100");

        ArgumentCaptor<CounterEvent> captor = ArgumentCaptor.forClass(CounterEvent.class);
        verify(eventProducer, times(1)).publish(captor.capture());

        CounterEvent event = captor.getValue();
        assertEquals(CounterSchema.EntityType.ARTICLE, event.entityType());
        assertEquals("1001", event.entityId());
        assertEquals("views", event.metric());
        assertEquals(0L, event.userId());
        assertEquals(1, event.delta());
    }

    @Test
    @DisplayName("测试 increaseView：5 分钟内重复访问（静默拦截，不触发 Kafka 异步递增）")
    void testIncreaseViewRepeatedVisit() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(false);

        counterService.increaseView(CounterSchema.EntityType.ARTICLE, "1001", 888L, "127.0.0.1");

        verify(eventProducer, never()).publish(any());
    }
}
