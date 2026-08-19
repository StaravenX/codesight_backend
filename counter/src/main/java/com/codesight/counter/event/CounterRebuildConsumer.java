package com.codesight.counter.event;

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
 * 灾难场景下的计数重建消费者：基于 earliest 回放历史事件，直接折叠到 SDS。
 * 默认关闭，仅当 counter.rebuild.enabled=true 时启用。
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "counter.rebuild.enabled", havingValue = "true")
public class CounterRebuildConsumer {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> incrScript;

    public CounterRebuildConsumer(StringRedisTemplate stringRedisTemplate, @Qualifier("incrFieldScript") RedisScript<Long> incrScript) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.incrScript = incrScript;
    }

    @KafkaListener(
            topics = CounterEvent.TOPIC,
            groupId = "counter-rebuild-#{T(java.lang.System).currentTimeMillis()}", // 全新group id，支持多次重复回放
            properties = {"auto.offset.reset=earliest"}
    )
    public void onMessage(CounterEvent event, Acknowledgment ack) {
        // 灾备场景：从最早位点回放历史事件，直接折叠到 SDS
        String sdsKey = CounterKeys.sdsKey(event.entityType(), event.entityId());
        try {
            stringRedisTemplate.execute(
                    incrScript,
                    List.of(sdsKey),
                    String.valueOf(CounterSchema.SCHEMA_LEN),
                    String.valueOf(CounterSchema.FIELD_SIZE),
                    String.valueOf(event.idx()),
                    String.valueOf(event.delta()));
            ack.acknowledge(); // 写入成功后提交位点，避免重复回放
        } catch (Exception ex) {
            log.error("灾难全量回放事件失败, event={}, sdsKey={}", event, sdsKey, ex);
            throw ex;
        }
    }
}