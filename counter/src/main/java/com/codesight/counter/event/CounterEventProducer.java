package com.codesight.counter.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 计数事件 Kafka 生产者。
 * <p>
 * 职责：
 * 1. 将计数的增量变动事件发送至 Kafka 主题；
 * 2. 按照业务实体 Key；
 * 3. 异步监听发送结果，记录失败告警与监控日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounterEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 投递计数变更事件
     *
     * @param event 领域事件模型
     */
    public void publish(CounterEvent event) {
        String partitionKey = event.entityType() + ":" + event.entityId();

        kafkaTemplate.send(CounterEvent.TOPIC, partitionKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 计数事件投递失败! key={}, event={}, error={}",
                                partitionKey, event, ex.getMessage(), ex);
                    }
                });
    }
}
