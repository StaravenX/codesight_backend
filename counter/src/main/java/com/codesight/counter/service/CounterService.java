package com.codesight.counter.service;

import com.codesight.counter.event.CounterEvent;
import com.codesight.counter.event.CounterEventProducer;
import com.codesight.counter.schema.BitmapShard;
import com.codesight.counter.schema.CounterKeys;
import com.codesight.counter.schema.CounterSchema;
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
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 通用高并发计数服务。
 * <p>
 * 核心能力：
 * 1. 状态事实层翻转与判重（4KB 分片位图 + Lua + Kafka 异步削峰）；
 * 2. 状态查询（判断当前用户是否已点赞/已收藏/已关注）；
 * 3. 16 字节定长二进制 SDS 纳秒级单查与 MGET 原生批量查询；
 * 4. 容灾自愈重建（Redisson 分布式锁 + 管道 BITCOUNT 真值回填）。
 */
@Slf4j
@Service
public class CounterService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> toggleBitScript;
    private final CounterEventProducer eventProducer;
    private final RedissonClient redisson;

    public CounterService(
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("toggleBitScript") RedisScript<Long> toggleBitScript,
            CounterEventProducer eventProducer,
            RedissonClient redisson) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.toggleBitScript = toggleBitScript;
        this.eventProducer = eventProducer;
        this.redisson = redisson;
    }

    /**
     * 实体状态翻转
     *
     * @param entityType 业务实体类型（如 "article", "user", "comment"）
     * @param entityId   业务实体唯一标识
     * @param metric     指标契约（如 Metric.LIKE, UserMetric.FOLLOWERS）
     * @param userId     操作用户 ID
     * @param isAdd      true 为正向添加(+1)，false 为反向取消(-1)
     * @return 状态是否发生真实改变（true 表示状态改变并触发了事件，false 表示幂等拦截）
     */
    public boolean toggle(String entityType, String entityId, CounterSchema.MetricItem metric, long userId, boolean isAdd) {
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
    public boolean isSet(String entityType, String entityId, CounterSchema.MetricItem metric, long userId) {
        long chunk = BitmapShard.chunkOf(userId);
        long bit = BitmapShard.bitOf(userId);
        String bmKey = CounterKeys.bitmapKey(entityType, entityId, metric.getCode(), chunk);
        Boolean state = stringRedisTemplate.opsForValue().getBit(bmKey, bit);
        return Boolean.TRUE.equals(state);
    }

    /**
     * 发送标量增量事件至 Kafka 进行异步写聚合
     *
     * @param entityType 业务实体类型（如 "article", "user"）
     * @param entityId   业务实体唯一标识
     * @param metric     指标契约（如 Metric.VIEWS, Metric.COMMENT, UserMetric.LIKES_RECEIVED）
     * @param delta      变动增量（如 +1, -1）
     */
    public void increase(String entityType, String entityId, CounterSchema.MetricItem metric, long userId, int delta) {
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
     * 获取单个实体的所有指标计数汇总（极速读取 16 字节 SDS 快照，若缺失自动触发自愈重建）
     *
     * @param entityType 实体类型
     * @param entityId   实体 ID
     * @return 各指标名称 -> 计数值 Map（如 "views" -> 50000, "like" -> 1200）
     */
    public Map<String, Long> getCounts(String entityType, String entityId) {
        String sdsKey = CounterKeys.sdsKey(entityType, entityId);
        byte[] raw = getRawBytes(sdsKey);
        Map<String, Long> counts = CounterSchema.decodeSds(entityType, raw);
        return counts != null ? counts : rebuild(entityType, entityId);
    }

    /**
     * 批量获取多个实体的指标计数汇总
     *
     * @param entityType 实体类型
     * @param entityIds  实体 ID 列表
     * @return 实体 ID -> 各指标计数值 Map
     */
    public Map<String, Map<String, Long>> batchGetCounts(String entityType, List<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<byte[]> keyBytesList = entityIds.stream()
                .map(id -> CounterKeys.sdsKey(entityType, id).getBytes(StandardCharsets.UTF_8))
                .toList();

        List<byte[]> rawList = stringRedisTemplate.execute((RedisCallback<List<byte[]>>) connection ->
                connection.stringCommands().mGet(keyBytesList.toArray(new byte[0][]))
        );

        Map<String, Map<String, Long>> resultMap = new HashMap<>();

        for (int i = 0; i < entityIds.size(); i++) {
            String entityId = entityIds.get(i);
            byte[] raw = (rawList != null && i < rawList.size()) ? rawList.get(i) : null;
            Map<String, Long> counts = CounterSchema.decodeSds(entityType, raw);
            resultMap.put(entityId, counts != null ? counts : rebuild(entityType, entityId));
        }

        return resultMap;
    }

    /**
     * 基于 4KB 分片位图事实层强制自愈重建 16 字节 SDS 计数快照
     *
     * @param entityType 实体类型
     * @param entityId   实体 ID
     * @return 重建后的最新计数值
     */
    public Map<String, Long> rebuild(String entityType, String entityId) {
        String sdsKey = CounterKeys.sdsKey(entityType, entityId);
        String lockKey = CounterKeys.rebuildLockKey(entityType, entityId);

        RLock lock = redisson.getLock(lockKey);
        boolean locked = false;
        CounterSchema.MetricItem[] metrics = CounterSchema.getMetrics(entityType);

        try {
            // 防击穿互斥保护：非阻塞抢锁
            locked = lock.tryLock(500, TimeUnit.MILLISECONDS);
            if (!locked) {
                Map<String, Long> res = CounterSchema.decodeSds(entityType, getRawBytes(sdsKey));
                if (res != null) return res;

                Map<String, Long> fallback = new HashMap<>();
                for (CounterSchema.MetricItem m : metrics) {
                    fallback.put(m.getCode(), bitCountShardsPipelined(m.getCode(), entityType, entityId));
                }
                return fallback;
            }

            // 双重检查锁（DCL）：检查其他并发线程是否已完成重建
            Map<String, Long> res = CounterSchema.decodeSds(entityType, getRawBytes(sdsKey));
            if (res != null) return res;

            log.info("触发 16B SDS 自愈重建: entityType={}, entityId={}", entityType, entityId);

            byte[] newSds = new byte[CounterSchema.TOTAL_BYTES];
            Map<String, Long> result = new HashMap<>();
            List<String> rebuildFields = new ArrayList<>();

            // 扫描位图事实层，管道化 BITCOUNT 统计各指标真值
            for (CounterSchema.MetricItem m : metrics) {
                long sum = bitCountShardsPipelined(m.getCode(), entityType, entityId);
                CounterSchema.writeInt32BE(newSds, m.offset(), sum);
                result.put(m.getCode(), sum);
                rebuildFields.add(String.valueOf(m.getIndex()));
            } 

            // 回填 16 字节 SDS 快照
            setRawBytes(sdsKey, newSds);

            // 清理 Hash 聚合桶中的对应字段，防止重复加算
            if (!rebuildFields.isEmpty()) {
                String aggKey = CounterKeys.aggKey(entityType, entityId);
                stringRedisTemplate.opsForHash().delete(aggKey, rebuildFields.toArray());
            }

            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("自愈重建被中断: entityType={}, entityId={}", entityType, entityId, e);
            return getDefaultZeroCounts(entityType);
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

    private long bitCountShardsPipelined(String metricCode, String entityType, String entityId) {
        String pattern = CounterKeys.bitmapKey(entityType, entityId, metricCode, 0)
                .replaceAll("0$", "*");

        Set<String> shardKeys = stringRedisTemplate.keys(pattern);
        if (shardKeys.isEmpty()) {
            return 0L;
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

    private Map<String, Long> getDefaultZeroCounts(String entityType) {
        Map<String, Long> map = new HashMap<>();
        for (CounterSchema.MetricItem m : CounterSchema.getMetrics(entityType)) {
            map.put(m.getCode(), 0L);
        }
        return map;
    }
}
