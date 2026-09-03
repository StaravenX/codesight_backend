package com.codesight.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultiLevelCacheTemplateTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Cache<String, DummyDTO> localCache;

    @Mock
    private Cache<String, List<DummyDTO>> localListCache;

    @InjectMocks
    private MultiLevelCacheTemplate cacheTemplate;

    public record DummyDTO(Long id, String name) {}

    @Test
    @DisplayName("测试单个对象查询：L1 本地缓存命中直接返回")
    void testGet_L1Hit() {
        DummyDTO dummy = new DummyDTO(1L, "测试");
        when(localCache.getIfPresent("key1")).thenReturn(dummy);

        AtomicInteger dbCallCount = new AtomicInteger(0);
        DummyDTO result = cacheTemplate.get(localCache, "key1", "redis:key1", DummyDTO.class, () -> {
            dbCallCount.incrementAndGet();
            return null;
        });

        assertNotNull(result);
        assertEquals(1L, result.id());
        assertEquals(0, dbCallCount.get());
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("测试单个对象查询：L1 穿透，L2 Redis 命中并回填 L1")
    void testGet_L2Hit() {
        when(localCache.getIfPresent("key1")).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("redis:key1")).thenReturn("{\"id\":1,\"name\":\"测试\"}");

        AtomicInteger dbCallCount = new AtomicInteger(0);
        DummyDTO result = cacheTemplate.get(localCache, "key1", "redis:key1", DummyDTO.class, () -> {
            dbCallCount.incrementAndGet();
            return null;
        });

        assertNotNull(result);
        assertEquals(1L, result.id());
        assertEquals("测试", result.name());
        assertEquals(0, dbCallCount.get());
        verify(localCache).put(eq("key1"), any());
    }

    @Test
    @DisplayName("测试单个对象查询：L1/L2 穿透，执行 DB 加载并双向回填")
    void testGet_L3DBFallback() {
        when(localCache.getIfPresent("key1")).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("redis:key1")).thenReturn(null);

        DummyDTO result = cacheTemplate.get(localCache, "key1", "redis:key1", DummyDTO.class, () -> new DummyDTO(1L, "数据库数据"));

        assertNotNull(result);
        assertEquals("数据库数据", result.name());
        verify(valueOperations).set(eq("redis:key1"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(localCache).put(eq("key1"), any());
    }

    @Test
    @DisplayName("测试单个对象查询：DB 返回 null 写入空值防穿透标记")
    void testGet_DBNullAntiPenetration() {
        when(localCache.getIfPresent("key1")).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("redis:key1")).thenReturn(null);

        DummyDTO result = cacheTemplate.get(localCache, "key1", "redis:key1", DummyDTO.class, () -> null);

        assertNull(result);
        verify(valueOperations).set(eq("redis:key1"), eq("{}"), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("测试集合列表查询：L1 穿透，L3 数据库加载并双向回填")
    void testGetList_L3DBFallback() {
        when(localListCache.getIfPresent("listKey")).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("redis:listKey")).thenReturn(null);

        List<DummyDTO> result = cacheTemplate.getList(
                localListCache, "listKey", "redis:listKey", DummyDTO.class,
                () -> List.of(new DummyDTO(1L, "列表项1"), new DummyDTO(2L, "列表项2"))
        );

        assertNotNull(result);
        assertEquals(2, result.size());
        verify(valueOperations).set(eq("redis:listKey"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(localListCache).put(eq("listKey"), any());
    }

    @Test
    @DisplayName("测试 SingleFlight 单飞机制：多个并发虚拟线程请求同一 Key，仅触发 1 次 DB 回源")
    void testSingleFlight_ConcurrentDeduplication() throws Exception {
        when(localCache.getIfPresent("sfKey")).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("redis:sfKey")).thenReturn(null);

        AtomicInteger dbCallCount = new AtomicInteger(0);
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        DummyDTO res = cacheTemplate.get(localCache, "sfKey", "redis:sfKey", DummyDTO.class, () -> {
                            dbCallCount.incrementAndGet();
                            try {
                                Thread.sleep(50); // 模拟耗时查库
                            } catch (InterruptedException ignored) {}
                            return new DummyDTO(888L, "单飞成功");
                        });
                        assertNotNull(res);
                        assertEquals(888L, res.id());
                    } catch (Exception e) {
                        fail("单飞并发测试异常: " + e.getMessage());
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }
            startLatch.countDown(); // 10 个虚拟线程同时起飞
            assertTrue(finishLatch.await(5, TimeUnit.SECONDS));
        }

        // 验证 10 个并发虚拟线程只调用了 1 次 DB 加载！
        assertEquals(1, dbCallCount.get());
    }

    @Test
    @DisplayName("测试淘汰缓存：同时失效 L1 和删除 L2")
    void testEvict() {
        cacheTemplate.evict(localCache, "key1", "redis:key1");

        verify(localCache).invalidate("key1");
        verify(stringRedisTemplate).delete("redis:key1");
    }
}
