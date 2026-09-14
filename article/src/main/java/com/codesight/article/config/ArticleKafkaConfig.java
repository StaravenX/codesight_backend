package com.codesight.article.config;

import com.codesight.article.event.ArticleSyncEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic 配置
 */
@Configuration
public class ArticleKafkaConfig {

    /**
     * 文章索引同步 Topic
     */
    @Bean
    public NewTopic articleSyncTopic() {
        return TopicBuilder.name(ArticleSyncEvent.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * 文章向量同步 Topic
     */
    @Bean
    public NewTopic articleVectorSyncTopic() {
        return TopicBuilder.name(ArticleSyncEvent.TOPIC_VECTOR)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
