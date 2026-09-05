package com.codesight.counter.service;

import com.codesight.counter.event.CounterEvent;
import com.codesight.counter.event.CounterEventProducer;
import com.codesight.counter.schema.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 通用高并发计数服务。
 * <p>
 * 核心能力：
 * 1. 状态事实层翻转与判重（4KB 分片位图 + Lua 脚本 + Kafka 异步削峰）；
 * 2. 状态查询与位图聚合（单用户 isSet 5微秒判重 + bitCountShards 管道化聚合统计）；
 * 3. 短时防刷与防抖去重（5分钟 PV 滑动防抖 increaseView ➔ 自动触发异步递增）；
 * 4. 标量增量异步聚合（纯标量 increase 异步投递 + N:1 倍写折叠）；
 * 5. 16 字节定长二进制 SDS 纳秒级单查与 MGET 原生批量查询；
 * 6. 容灾自愈重建体系（Redisson 分布式锁防击穿 + DCL 双重检查 + CounterRebuilder SPI 策略路由）。
 */
@Slf4j
@Service
public class CounterService {

    private static final Duration PV_DEDUP_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> toggleBitScript;
    private final CounterEventProducer eventProducer;
    private final RedissonClient redisson;
    private final Map<CounterSchema.EntityType, CounterRebuilder> rebuilderMap = new ConcurrentHashMap<>();

    public CounterService(
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("toggleBitScript") RedisScript<Long> toggleBitScript,
            CounterEventProducer eventProducer,
            RedissonClient redisson,
            List<CounterRebuilder> rebuilders) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.toggleBitScript = toggleBitScript;
        this.eventProducer = eventProducer;
        this.redisson = redisson;
        if (rebuilders != null) {
            for (CounterRebuilder rebuilder : rebuilders) {
                this.rebuilderMap.put(rebuilder.entityType(), rebuilder);
            }
        }
    }

    /**
     * 实体状态翻转
     *
     * @param entityType 业务实体类型（如 "article", "user", "comment"）
     * @param entityId   业务实体唯一标识
     * @param metric     指标契约（如 ArticleMetric.LIKE, UserMetric.FOLLOWERS）
     * @param userId     操作用户 ID
     * @param isAdd      true 为正向添加(+1)，false 为反向取消(-1)
     * @return 状态是否发生真实改变（true 表示状态改变并触发了事件，false 表示幂等拦截）
     */
    public boolean toggle(CounterSchema.EntityType entityType, String entityId, CounterSchema.MetricItem metric, long userId, boolean isAdd) {
        long chunk = BitmapShard.chunkOf(userId);
        long bit = BitmapShard.bitOf(userId);
        String bmKey = CounterKeys.bitmapKey(entityType, entityId, metric.getCode(), chunk);

        // 1. 调用 Lua 脚本在 4KB 分片位图中执行原子翻转与状态判重
        Long changed = stringRedisTemplate.execute(
                toggleBitScript,
                Collections.singletonList(bmKey),
                String.valueOf(bit),
                isAdd ? "add" : "remove"
        );

        boolean isStateChanged = changed == 1L;

        // 2. 仅当状态发生真实改变时，生产增量事件并投递至 Kafka
        if (isStateChanged) {
            int delta = isAdd ? 1 : -1;
            increase(entityType, entityId, metric, userId, delta);
        }
        return isStateChanged;
    }

    /**
     * 查询用户对某实体是否处于激活状态（如“是否已点赞/已收藏/已关注”）
     */
    public boolean isSet(CounterSchema.EntityType entityType, String entityId, CounterSchema.MetricItem metric, long userId) {
        long chunk = BitmapShard.chunkOf(userId);
        long bit = BitmapShard.bitOf(userId);
        String bmKey = CounterKeys.bitmapKey(entityType, entityId, metric.getCode(), chunk);
        Boolean state = stringRedisTemplate.opsForValue().getBit(bmKey, bit);
        return Boolean.TRUE.equals(state);
    }

    /**
     * 管道化批量查询用户对多个实体的状态是否激活
     *
     * @param entityType 业务实体类型
     * @param entityIds  业务实体 ID 列表
     * @param metric     指标契约（如 ArticleMetric.LIKE）
     * @param userId     当前登录用户 ID
     * @return 实体 ID -> 是否激活 Map
     */
    public Map<String, Boolean> batchIsSet(CounterSchema.EntityType entityType, List<String> entityIds, CounterSchema.MetricItem metric, long userId) {
        if (entityIds == null || entityIds.isEmpty() || userId <= 0) {
            return Collections.emptyMap();
        }

        long chunk = BitmapShard.chunkOf(userId);
        long bit = BitmapShard.bitOf(userId);

        List<Object> results = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String entityId : entityIds) {
                String bmKey = CounterKeys.bitmapKey(entityType, entityId, metric.getCode(), chunk);
                connection.stringCommands().getBit(bmKey.getBytes(StandardCharsets.UTF_8), bit);
            }
            return null;
        });

        Map<String, Boolean> isSetMap = new HashMap<>(entityIds.size());
        for (int i = 0; i < entityIds.size(); i++) {
            Object res = i < results.size() ? results.get(i) : null;
            isSetMap.put(entityIds.get(i), Boolean.TRUE.equals(res));
        }
        return isSetMap;
    }

    /**
     * 管道化扫描并统计指定实体与指标的所有 4KB 分片位图总数（BITCOUNT）
     *
     * @param entityType 业务实体类型（如 ARTICLE, USER）
     * @param entityId   业务实体唯一标识
     * @param metric     指标枚举契约
     * @return 该指标当前在所有 4KB 分片位图中的状态激活总人数
     */
    public long bitCountShards(CounterSchema.EntityType entityType, String entityId, CounterSchema.MetricItem metric) {
        String pattern = CounterKeys.bitmapKey(entityType, entityId, metric.getCode(), 0)
                .replaceAll("0$", "*");

        Set<String> shardKeys = stringRedisTemplate.keys(pattern);
        if (shardKeys.isEmpty()) {
            return -1L;
        }

        List<byte[]> keys = shardKeys.stream()
                .map(k -> k.getBytes(StandardCharsets.UTF_8))
                .toList();

        List<Object> counts = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            RedisStringCommands stringCommands = connection.stringCommands();
            for (byte[] k : keys) {
                stringCommands.bitCount(k);
            }
            return null;
        });

        long total = 0L;
        for (Object cnt : counts) {
            if (cnt instanceof Long val) {
                total += val;
            }
        }
        return total;
    }

    /**
     * 发送标量增量事件至 Kafka 进行异步写聚合
     *
     * @param entityType 业务实体类型（如 "article", "user"）
     * @param entityId   业务实体唯一标识
     * @param metric     指标契约（如 ArticleMetric.VIEWS, ArticleMetric.COMMENT, UserMetric.LIKES_RECEIVED）
     * @param delta      变动增量（如 +1, -1）
     */
    public void increase(CounterSchema.EntityType entityType, String entityId, CounterSchema.MetricItem metric, long userId, int delta) {
        if(delta == 0) return;

        eventProducer.publish(CounterEvent.of(
                entityType,
                entityId,
                metric.getCode(),
                metric.getIndex(),
                userId,
                delta
        ));
    }

    /**
     * 增加浏览量（自动执行 5 分钟短时防刷）
     *
     * @param entityType 业务实体类型（如 "article"）
     * @param entityId   业务实体唯一标识
     * @param userId     当前登录用户 ID（可为空）
     * @param clientIp   客户端 IP 地址（游客防刷依据）
     */
    public void increaseView(CounterSchema.EntityType entityType, String entityId, Long userId, String clientIp) {
        String identifier = (userId != null) ? "u:" + userId : "ip:" + (clientIp != null ? clientIp : "unknown");
        String dedupKey = CounterKeys.pvDedupKey(entityType, entityId, identifier);

        Boolean isFirstVisit = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "1", PV_DEDUP_TTL);

        if (Boolean.TRUE.equals(isFirstVisit)) {
            increase(
                    entityType,
                    entityId,
                    CounterSchema.ArticleMetric.VIEWS,
                    userId != null ? userId : 0L,
                    1
            );
        }
    }

    /**
     * 获取单个实体的所有指标计数汇总（极速读取 16 字节 SDS 快照，若缺失自动触发自愈重建）
     *
     * @param entityType 实体类型
     * @param entityId   实体 ID
     * @return 各指标契约 -> 计数值 Map（如 ArticleMetric.VIEWS -> 50000, ArticleMetric.LIKE -> 1200）
     */
    public Map<CounterSchema.MetricItem, Long> getCounts(CounterSchema.EntityType entityType, String entityId) {
        String sdsKey = CounterKeys.sdsKey(entityType, entityId);
        byte[] raw = getRawBytes(sdsKey);
        Map<CounterSchema.MetricItem, Long> counts = CounterSchema.decodeSds(entityType, raw);
        return counts != null ? counts : rebuild(entityType, entityId);
    }

    /**
     * 批量获取多个实体的指标计数汇总
     *
     * @param entityType 实体类型
     * @param entityIds  实体 ID 列表
     * @return 实体 ID -> 各指标计数值 Map
     */
    public Map<String, Map<CounterSchema.MetricItem, Long>> batchGetCounts(CounterSchema.EntityType entityType, List<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<byte[]> keyBytesList = entityIds.stream()
                .map(id -> CounterKeys.sdsKey(entityType, id).getBytes(StandardCharsets.UTF_8))
                .toList();

        List<byte[]> rawList = stringRedisTemplate.execute((RedisCallback<List<byte[]>>) connection ->
                connection.stringCommands().mGet(keyBytesList.toArray(new byte[0][]))
        );

        Map<String, Map<CounterSchema.MetricItem, Long>> resultMap = new HashMap<>();

        for (int i = 0; i < entityIds.size(); i++) {
            String entityId = entityIds.get(i);
            byte[] raw = (rawList != null && i < rawList.size()) ? rawList.get(i) : null;
            Map<CounterSchema.MetricItem, Long> counts = CounterSchema.decodeSds(entityType, raw);
            resultMap.put(entityId, counts != null ? counts : rebuild(entityType, entityId));
        }

        return resultMap;
    }

    /**
     * 调用相应模块的 rebuilder 实现类重建 16 字节 SDS 计数快照
     *
     * @param entityType 实体类型
     * @param entityId   实体 ID
     * @return 重建后的最新计数值
     */
    public Map<CounterSchema.MetricItem, Long> rebuild(CounterSchema.EntityType entityType, String entityId) {
        String sdsKey = CounterKeys.sdsKey(entityType, entityId);
        String lockKey = CounterKeys.rebuildLockKey(entityType, entityId);

        RLock lock = redisson.getLock(lockKey);
        boolean locked = false;
        CounterSchema.MetricItem[] metrics = entityType.getMetrics();

        try {
            // 防击穿互斥保护：非阻塞抢锁
            locked = lock.tryLock(500, TimeUnit.MILLISECONDS);
            if (!locked) {
                Map<CounterSchema.MetricItem, Long> res = CounterSchema.decodeSds(entityType, getRawBytes(sdsKey));
                if (res != null) return res;

                CounterRebuilder rebuilder = rebuilderMap.get(entityType);
                return rebuilder != null ? rebuilder.rebuild(entityId) : getDefaultZeroCounts(entityType);
            }

            // 双重检查锁（DCL）：检查其他并发线程是否已完成重建
            Map<CounterSchema.MetricItem, Long> res = CounterSchema.decodeSds(entityType, getRawBytes(sdsKey));
            if (res != null) return res;

            log.info("触发 16B SDS 自愈重建: entityType={}, entityId={}", entityType, entityId);

            CounterRebuilder rebuilder = rebuilderMap.get(entityType);
            Map<CounterSchema.MetricItem, Long> result = (rebuilder != null)
                    ? rebuilder.rebuild(entityId)
                    : getDefaultZeroCounts(entityType);

            byte[] newSds = new byte[CounterSchema.TOTAL_BYTES];

            for (CounterSchema.MetricItem m : metrics) {
                long val = result.getOrDefault(m, 0L);
                CounterSchema.writeInt32BE(newSds, m.offset(), val);

                // 清理 Hash 聚合桶中的对应字段，防止重复加算
                String aggKey = CounterKeys.aggKey(entityType, entityId);
                stringRedisTemplate.opsForHash().delete(aggKey, m.getIndex());
            }

            // 回填 16 字节 SDS 快照
            setRawBytes(sdsKey, newSds);

            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("自愈重建被中断: entityType={}, entityId={}", entityType, entityId, e);
            Map<CounterSchema.MetricItem, Long> lastCheck = CounterSchema.decodeSds(entityType, getRawBytes(sdsKey));
            return lastCheck != null ? lastCheck : getDefaultZeroCounts(entityType);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 从 Redis 获取原始字节数组
     * @param key Redis Key
     * @return 原始字节数组
     */
    private byte[] getRawBytes(String key) {
        return stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8))
        );
    }

    /**
     * 向 Redis 设置原始字节数组
     * @param key Redis Key
     * @param bytes 原始字节数组
     */
    private void setRawBytes(String key, byte[] bytes) {
        stringRedisTemplate.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), bytes)
        );
    }

    private Map<CounterSchema.MetricItem, Long> getDefaultZeroCounts(CounterSchema.EntityType entityType) {
        Map<CounterSchema.MetricItem, Long> map = new HashMap<>();
        for (CounterSchema.MetricItem m : entityType.getMetrics()) {
            map.put(m, 0L);
        }
        return map;
    }
}
