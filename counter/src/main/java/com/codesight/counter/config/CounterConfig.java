package com.codesight.counter.config;

import com.codesight.counter.event.CounterEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
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

    /**
     * 16 字节 SDS 原位原子累加 Lua 脚本
     */
    @Bean("incrFieldScript")
    public RedisScript<Long> incrFieldScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/incr_field.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Hash 暂存桶原子安全扣减 Lua 脚本
     */
    @Bean("decrFieldScript")
    public RedisScript<Long> decrFieldScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/decr_field.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
