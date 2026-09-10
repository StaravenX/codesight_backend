package com.codesight.search.event;

import com.codesight.article.event.ArticleSyncEvent;
import com.codesight.search.index.SearchIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 监听 Kafka 文章变更事件，同步更新 Elasticsearch 索引
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleSearchKafkaConsumer {

    private final SearchIndexService searchIndexService;

    @KafkaListener(topics = ArticleSyncEvent.TOPIC, groupId = "codesight-search-sync")
    public void onMessage(ArticleSyncEvent event, Acknowledgment ack) {
        try {
            if (event == null || event.articleId() == null || event.action() == null) {
                if (ack != null) {
                    ack.acknowledge();
                }
                return;
            }

            switch (event.action()) {
                case UPSERT -> searchIndexService.upsertArticle(event.articleId());
                case DELETE -> searchIndexService.deleteArticle(event.articleId());
            }

            if (ack != null) {
                ack.acknowledge();
            }
        } catch (Exception e) {
            log.error("消费文章搜索同步 Kafka 事件异常, event={}", event, e);
            if (ack != null) {
                ack.acknowledge();
            }
        }
    }
}
