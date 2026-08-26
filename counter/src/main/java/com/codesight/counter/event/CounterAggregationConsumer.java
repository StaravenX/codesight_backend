package com.codesight.counter.event;

import com.codesight.counter.schema.CounterKeys;
import com.codesight.counter.schema.CounterSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计数事件聚合与定时批量刷写消费者。
 * <p>
 * 职责：
 * 1. 消费 Kafka 中的全站互动增量事件（文章点赞/收藏、创作者涨粉/获赞、评论点赞等），写入 Redis Hash 增量暂存桶（削峰缓冲）；
 * 2. 基于定时任务（每 1 秒执行）将聚合增量批量折叠刷写到对应实体的 16 字节二进制 SDS 快照；
 * 3. 刷写成功后通过 Lua 脚本原子扣减并清理已归零的聚合桶字段，防止并发丢失与重复加算。
 */
@Slf4j
@Service
public class CounterAggregationConsumer {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> incrScript;
    private final RedisScript<Long> decrScript;

    public CounterAggregationConsumer(
            StringRedisTemplate redis,
            @Qualifier("incrFieldScript") RedisScript<Long> incrScript,
            @Qualifier("decrFieldScript") RedisScript<Long> decrScript) {
        this.redis = redis;
        this.incrScript = incrScript;
        this.decrScript = decrScript;
    }

    /**
     * 消费 Kafka 计数增量事件并暂存至 Redis Hash 聚合桶
     */
    @KafkaListener(topics = CounterEvent.TOPIC, groupId = "counter-agg")
    public void onMessage(CounterEvent counterEvent, Acknowledgment ack) {
        try {
            String aggKey = CounterKeys.aggKey(counterEvent.entityType(), counterEvent.entityId());
            String field = String.valueOf(counterEvent.idx());

            redis.opsForHash().increment(aggKey, field, counterEvent.delta());

            ack.acknowledge();
        } catch (Exception ex) {
            log.error("消费计数事件失败, event={}", counterEvent, ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 每 1 秒定时将 Redis Hash 聚合桶中的增量折叠刷写入 16 字节二进制 SDS
     */
    @Scheduled(fixedDelay = 1000L)
    public void flush() {
        Set<String> keys = redis.keys("agg:" + CounterSchema.SCHEMA_ID + ":*");
        if (keys.isEmpty()) {
            return;
        }

        // 遍历在过去 1 秒内产生过计数变动的所有业务实体（如文章、创作者、评论等对应的 Hash 聚合桶）
        for (String aggKey : keys) {
            Map<Object, Object> entries = redis.opsForHash().entries(aggKey);

            String[] parts = aggKey.split(":", 4);

            CounterSchema.EntityType entityType = CounterSchema.EntityType.valueOf(parts[2].toUpperCase());
            String entityId = parts[3];

            String sdsKey = CounterKeys.sdsKey(entityType, entityId);

            // 遍历当前实体在过去 1 秒内发生过增量变动的各个具体指标（如阅读、点赞、收藏等字段）
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                String field = String.valueOf(e.getKey());
                int idx = Integer.parseInt(String.valueOf(e.getKey()));
                long delta = Long.parseLong(String.valueOf(e.getValue()));

                if (delta == 0) {
                    continue;
                }

                try {
                    // 1. 调用 Lua 脚本原位原子累加到 16 字节 SDS
                    redis.execute(
                            incrScript,
                            List.of(sdsKey),
                            String.valueOf(CounterSchema.SCHEMA_LEN),
                            String.valueOf(CounterSchema.FIELD_SIZE),
                            String.valueOf(idx),
                            String.valueOf(delta)
                    );

                    // 2. 累加成功后扣减 Hash 暂存桶中的对应增量（若归零则自动删除字段）
                    redis.execute(
                            decrScript,
                            List.of(aggKey),
                            field,
                            String.valueOf(delta)
                    );
                } catch (Exception ex) {
                    log.error("刷写计数增量失败, aggKey={}, field={}, delta={}", aggKey, field, delta, ex);
                }
            }

            // 如 Hash 桶中所有字段均已处理归零，清理该桶 Key
            Long size = redis.opsForHash().size(aggKey);
            if (size == 0L) {
                redis.delete(aggKey);
            }
        }
    }
}
