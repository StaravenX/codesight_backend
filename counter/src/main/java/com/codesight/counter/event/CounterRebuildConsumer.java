package com.codesight.counter.event;

import com.codesight.counter.schema.BitmapShard;
import com.codesight.counter.schema.CounterKeys;
import com.codesight.counter.schema.CounterSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 灾难场景下的计数与状态全量自愈消费者：
 * 基于 earliest 从头全量回放历史事件，同步重建 4KB 分片位图与 16B SDS 快照。
 * 默认关闭，仅当 counter.rebuild.enabled=true 时启用。
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "counter.rebuild.enabled", havingValue = "true")
public class CounterRebuildConsumer {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> incrScript;
    private final RedisScript<Long> toggleBitScript;

    public CounterRebuildConsumer(
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("incrFieldScript") RedisScript<Long> incrScript,
            @Qualifier("toggleBitScript") RedisScript<Long> toggleBitScript) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.incrScript = incrScript;
        this.toggleBitScript = toggleBitScript;
    }

    @KafkaListener(
            topics = CounterEvent.TOPIC,
            groupId = "counter-rebuild-#{T(java.lang.System).currentTimeMillis()}", // 全新group id，支持多次重复回放
            properties = {"auto.offset.reset=earliest"}
    )
    public void onMessage(CounterEvent event, Acknowledgment ack) {
        String sdsKey = CounterKeys.sdsKey(event.entityType(), event.entityId());
        try {
            CounterSchema.MetricItem metricItem = event.entityType().getMetrics()[event.idx()];
            long bitChanged = 0L;

            // 1. 状态事实层重建：当且仅当该指标具有位图事实层契约（isBitmapBacked）且为有效用户互动时，使用lua脚本翻转
            if (metricItem.isBitmapBacked() && event.userId() > 0) {
                long chunk = BitmapShard.chunkOf(event.userId());
                long bitOffset = BitmapShard.bitOf(event.userId());
                String bitmapKey = CounterKeys.bitmapKey(event.entityType(), event.entityId(), event.metric(), chunk);
                String op = (event.delta() > 0) ? "add" : "remove";

                bitChanged = stringRedisTemplate.execute(
                        toggleBitScript,
                        List.of(bitmapKey),
                        String.valueOf(bitOffset),
                        op
                );
            }

            // 2. 内存快照层重建：将增量折叠至 16B SDS 紧凑快照中
            if (bitChanged == 1L) {
                stringRedisTemplate.execute(
                        incrScript,
                        List.of(sdsKey),
                        String.valueOf(CounterSchema.SCHEMA_LEN),
                        String.valueOf(CounterSchema.FIELD_SIZE),
                        String.valueOf(event.idx()),
                        String.valueOf(event.delta()));
            }

            ack.acknowledge(); // 写入成功后提交位点
        } catch (Exception ex) {
            log.error("灾难全量回放事件失败, event={}, sdsKey={}", event, sdsKey, ex);
            throw ex;
        }
    }
}