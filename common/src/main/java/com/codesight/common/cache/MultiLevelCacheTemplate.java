package com.codesight.common.cache;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 通用多级缓存执行模板（L1 Caffeine + L2 Redis + L3 Mysql）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiLevelCacheTemplate {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String EMPTY_OBJECT_FLAG = "{}";
    private static final String EMPTY_LIST_FLAG = "[]";

    private static final Duration DEFAULT_BASE_TTL = Duration.ofDays(1);
    private static final Duration DEFAULT_JITTER_TTL = Duration.ofMinutes(30);
    private static final Duration DEFAULT_EMPTY_TTL = Duration.ofMinutes(5);

    /**
     * SingleFlight 并发飞行任务注册表
     */
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlightMap = new ConcurrentHashMap<>();

    /**
     * 查询单个对象（采用默认时间）
     */
    public <K, V> V get(
            Cache<K, V> localCache,
            K localKey,
            String redisKey,
            Class<V> targetType,
            Supplier<V> dbLoader
    ) {
        return get(localCache, localKey, redisKey, targetType, dbLoader, DEFAULT_BASE_TTL, DEFAULT_JITTER_TTL, DEFAULT_EMPTY_TTL);
    }

    /**
     * 查询单个对象
     */
    public <K, V> V get(
            Cache<K, V> localCache,
            K localKey,
            String redisKey,
            Class<V> targetType,
            Supplier<V> dbLoader,
            Duration baseTtl,
            Duration jitterTtl,
            Duration emptyTtl
    ) {
        if (localKey == null || redisKey == null || targetType == null) {
            return null;
        }

        // L1 Caffeine 本地缓存
        V l1Val = localCache.getIfPresent(localKey);
        if (l1Val != null) {
            return l1Val;
        }

        // L2 Redis 分布式缓存
        String redisJson = stringRedisTemplate.opsForValue().get(redisKey);
        if (redisJson != null) {
            if (isFlagEmpty(redisJson)) {
                return null;
            }
            V l2Val = JSONUtil.toBean(redisJson, targetType);
            backfillL1(localCache, localKey, l2Val);
            return l2Val;
        }

        // L3 数据库（单飞锁）
        return executeWithSingleFlight(redisKey, () -> {
            // double check
            String doubleCheckJson = stringRedisTemplate.opsForValue().get(redisKey);
            if (doubleCheckJson != null) {
                if (isFlagEmpty(doubleCheckJson)) {
                    return null;
                }
                V recheckVal = JSONUtil.toBean(doubleCheckJson, targetType);
                backfillL1(localCache, localKey, recheckVal);
                return recheckVal;
            }

            V dbVal = (dbLoader != null) ? dbLoader.get() : null;
            if (dbVal == null) {
                stringRedisTemplate.opsForValue().set(
                        redisKey,
                        EMPTY_OBJECT_FLAG,
                        emptyTtl.toSeconds(),
                        TimeUnit.SECONDS
                );
                return null;
            }

            // 回填 L2 与 L1 缓存
            backfillL2AndL1(localCache, localKey, redisKey, dbVal, baseTtl, jitterTtl);
            return dbVal;
        });
    }

    /**
     * 查询 List 集合
     */
    public <K, T> List<T> getList(
            Cache<K, List<T>> localCache,
            K localKey,
            String redisKey,
            Class<T> elementType,
            Supplier<List<T>> dbLoader
    ) {
        return getList(localCache, localKey, redisKey, elementType, dbLoader, DEFAULT_BASE_TTL, DEFAULT_JITTER_TTL, DEFAULT_EMPTY_TTL);
    }

    /**
     * 查询 List 集合
     */
    public <K, T> List<T> getList(
            Cache<K, List<T>> localCache,
            K localKey,
            String redisKey,
            Class<T> elementType,
            Supplier<List<T>> dbLoader,
            Duration baseTtl,
            Duration jitterTtl,
            Duration emptyTtl
    ) {
        if (localKey == null || redisKey == null || elementType == null) {
            return Collections.emptyList();
        }

        // L1 Caffeine 本地缓存
        List<T> l1List = localCache.getIfPresent(localKey);
        if (l1List != null) {
            return l1List;
        }

        // L2 Redis 分布式缓存
        String redisJson = stringRedisTemplate.opsForValue().get(redisKey);
        if (redisJson != null) {
            if (isFlagEmpty(redisJson)) {
                return Collections.emptyList();
            }
            List<T> l2List = JSONUtil.toList(redisJson, elementType);
            backfillL1(localCache, localKey, l2List);
            return l2List;
        }

        // L3 数据库（单飞锁）
        return executeWithSingleFlight(redisKey, () -> {
            // double check
            String doubleCheckJson = stringRedisTemplate.opsForValue().get(redisKey);
            if (doubleCheckJson != null) {
                if (isFlagEmpty(doubleCheckJson)) {
                    return Collections.emptyList();
                }
                List<T> recheckList = JSONUtil.toList(doubleCheckJson, elementType);
                backfillL1(localCache, localKey, recheckList);
                return recheckList;
            }

            List<T> dbList = (dbLoader != null) ? dbLoader.get() : null;
            if (dbList == null || dbList.isEmpty()) {
                stringRedisTemplate.opsForValue().set(
                        redisKey,
                        EMPTY_LIST_FLAG,
                        emptyTtl.toSeconds(),
                        TimeUnit.SECONDS
                );
                return Collections.emptyList();
            }

            // 回填 L2 与 L1 缓存
            backfillL2AndL1(localCache, localKey, redisKey, dbList, baseTtl, jitterTtl);
            return dbList;
        });
    }

    /**
     * 主动淘汰单条缓存
     */
    public <K, V> void evict(Cache<K, V> localCache, K localKey, String redisKey) {
        if (localKey != null) {
            localCache.invalidate(localKey);
        }
        if (redisKey != null) {
            stringRedisTemplate.delete(redisKey);
        }
    }

    /**
     * 主动淘汰全量缓存
     */
    public <K, V> void evictAll(Cache<K, V> localCache, String redisKey) {
        localCache.invalidateAll();
        if (redisKey != null) {
            stringRedisTemplate.delete(redisKey);
        }
    }

    /**
     * 回填 L1 本地缓存
     */
    private <K, V> void backfillL1(Cache<K, V> localCache, K localKey, V data) {
        if (data != null) {
            localCache.put(localKey, data);
        }
    }

    /**
     * 回填 L2 Redis 与 L1 本地缓存
     */
    private <K, V> void backfillL2AndL1(
            Cache<K, V> localCache,
            K localKey,
            String redisKey,
            V data,
            Duration baseTtl,
            Duration jitterTtl
    ) {
        long jitterSeconds = jitterTtl.toSeconds() > 0
                ? ThreadLocalRandom.current().nextLong(jitterTtl.toSeconds())
                : 0L;
        long totalTtl = baseTtl.toSeconds() + jitterSeconds;

        stringRedisTemplate.opsForValue().set(
                redisKey,
                JSONUtil.toJsonStr(data),
                totalTtl,
                TimeUnit.SECONDS
        );
        backfillL1(localCache, localKey, data);
    }

    private boolean isFlagEmpty(String json) {
        return EMPTY_OBJECT_FLAG.equals(json) || EMPTY_LIST_FLAG.equals(json);
    }

    /**
     * SingleFlight 核心执行器
     */
    @SuppressWarnings("unchecked")
    private <V> V executeWithSingleFlight(String key, Supplier<V> loader) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlightMap.putIfAbsent(key, future);

        if (existing == null) {
            try {
                V result = loader.get();
                future.complete(result);
                return result;
            } catch (Throwable t) {
                future.completeExceptionally(t);
                throw t instanceof RuntimeException re ? re : new RuntimeException(t);
            } finally {
                inFlightMap.remove(key);
            }
        } else {
            try {
                Object joined = existing.join();
                return (V) joined;
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
            }
        }
    }
}
