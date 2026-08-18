package com.codesight.counter.config;

import com.codesight.counter.event.CounterEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * 计数模块核心配置
 */
@Configuration
public class CounterConfig {

    /**
     * JSON 消息转换器
     */
    @Bean
    public RecordMessageConverter recordMessageConverter() {
        return new StringJsonMessageConverter();
    }

    /**
     * 自动初始化计数事件 Topic
     */
    @Bean
    public NewTopic counterEventsTopic() {
        return TopicBuilder.name(CounterEvent.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

}
