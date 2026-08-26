package com.codesight.counter.event;

import com.codesight.counter.schema.CounterKeys;
import com.codesight.counter.schema.CounterSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CounterAggregationConsumer 削峰聚合消费者单元测试")
class CounterAggregationConsumerTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private RedisScript<Long> incrScript;

    @Mock
    private RedisScript<Long> decrScript;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private Acknowledgment ack;

    private CounterAggregationConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CounterAggregationConsumer(redis, incrScript, decrScript);
    }

    @Test
    @DisplayName("测试 onMessage：收到 Kafka 增量事件写入 Redis Hash 暂存桶并提交位点")
    void testOnMessage() {
        when(redis.opsForHash()).thenReturn(hashOperations);

        CounterEvent event = CounterEvent.of(CounterSchema.EntityType.ARTICLE, "1001", "like", 1, 99L, 1);
        consumer.onMessage(event, ack);

        String expectedAggKey = CounterKeys.aggKey(CounterSchema.EntityType.ARTICLE, "1001");
        verify(hashOperations, times(1)).increment(expectedAggKey, "1", 1);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    @DisplayName("测试 flush：定时任务批量刷写聚合增量至 16B SDS 并原子扣减")
    void testFlush() {
        String aggKey = "agg:v1:article:1001";
        when(redis.keys(anyString())).thenReturn(Collections.singleton(aggKey));
        when(redis.opsForHash()).thenReturn(hashOperations);

        Map<Object, Object> entries = new HashMap<>();
        entries.put("1", "5"); // like +5
        when(hashOperations.entries(aggKey)).thenReturn(entries);

        consumer.flush();

        // 验证调用了 incrScript (写入 SDS) 与 decrScript (扣减 Hash 桶)
        verify(redis, times(1)).execute(eq(incrScript), anyList(), any(), any(), any(), any());
        verify(redis, times(1)).execute(eq(decrScript), anyList(), any(), any());
    }
}
