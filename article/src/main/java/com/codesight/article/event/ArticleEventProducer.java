package com.codesight.article.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 文章事件 Kafka 生产者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 发送文章搜索同步事件
     * 以 articleId 作为 partition key 保证同一文章的变更严格保序
     */
    public void sendSyncEvent(Long articleId, ArticleSyncEvent.Action action) {
        if (articleId == null || action == null) {
            return;
        }
        String partitionKey = String.valueOf(articleId);
        ArticleSyncEvent event = new ArticleSyncEvent(articleId, action);

        kafkaTemplate.send(ArticleSyncEvent.TOPIC, partitionKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 文章同步事件投递失败: articleId={}, action={}, error={}",
                                articleId, action, ex.getMessage(), ex);
                    }
                });
    }
}
