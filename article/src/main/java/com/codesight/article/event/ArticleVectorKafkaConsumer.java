package com.codesight.article.event;

import com.codesight.ai.service.ArticleVectorService;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 监听 Kafka 文章变更事件，异步生成或清除文章向量资产
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleVectorKafkaConsumer {

    private final ArticleMapper articleMapper;
    private final ArticleVectorService articleVectorService;

    @KafkaListener(topics = ArticleSyncEvent.TOPIC, groupId = "codesight-article-vector-sync")
    public void onMessage(ArticleSyncEvent event, Acknowledgment ack) {
        try {
            if (event == null || event.articleId() == null || event.action() == null) {
                acknowledge(ack);
                return;
            }

            switch (event.action()) {
                case UPSERT -> handleUpsert(event.articleId());
                case DELETE -> articleVectorService.deleteArticleVector(event.articleId());
            }

            acknowledge(ack);
        } catch (Exception e) {
            log.error("消费文章向量同步 Kafka 事件异常, event={}", event, e);
            acknowledge(ack);
        }
    }

    private void handleUpsert(Long articleId) {
        Article article = articleMapper.selectById(articleId);
        if (article != null && article.getStatus() == ArticleStatus.PUBLISHED && article.getVisible() == ArticleVisible.PUBLIC) {
            articleVectorService.generateAndSaveVector(
                    article.getId(),
                    article.getTitle(),
                    article.getSummary(),
                    article.getContentMd()
            );
        }
    }

    private void acknowledge(Acknowledgment ack) {
        if (ack != null) {
            ack.acknowledge();
        }
    }
}
