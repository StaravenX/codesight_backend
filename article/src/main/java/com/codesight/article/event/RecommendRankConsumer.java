package com.codesight.article.event;

import com.codesight.article.service.RecommendRankService;
import com.codesight.counter.event.CounterEvent;
import com.codesight.counter.schema.CounterSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 推荐流实时打分消费者
 * <p>
 * 职责：
 * 1. 监听计数系统发出的全站用户互动事件流（点赞、阅读、收藏、评论）；
 * 2. 针对文章实体实时推高推荐候选池排序分（ZINCRBY）
 * 3. 记录文章变更脏标记（Redis Set: counter:dirty:articles），驱动异步定时落盘持久化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendRankConsumer {

    public static final String DIRTY_ARTICLES_KEY = "counter:dirty:articles";

    private final RecommendRankService recommendRankService;
    private final StringRedisTemplate redis;

    @KafkaListener(topics = CounterEvent.TOPIC, groupId = "article-recommend-rank")
    public void onMessage(CounterEvent event, Acknowledgment ack) {
        try {
            if (event == null || event.entityType() != CounterSchema.EntityType.ARTICLE) {
                if (ack != null) {
                    ack.acknowledge();
                }
                return;
            }

            Long articleId = Long.parseLong(event.entityId());
            double weight = getMetricWeight(event.metric());
            double deltaScore = weight * event.delta();

            if (deltaScore != 0.0) {
                // 实时推高推荐候选池分数
                recommendRankService.addOrIncrScore(articleId, deltaScore);

                // 标记实体，便于写聚合落库
                redis.opsForSet().add(DIRTY_ARTICLES_KEY, String.valueOf(articleId));
            }

            if (ack != null) {
                ack.acknowledge();
            }
        } catch (Exception e) {
            log.error("处理推荐打分事件异常, event={}", event, e);
            if (ack != null) {
                ack.acknowledge();
            }
        }
    }

    private double getMetricWeight(String metric) {
        if (metric == null) {
            return 0.0;
        }
        return switch (metric.toLowerCase()) {
            case "views", "view" -> 1.0;
            case "like", "likes" -> 5.0;
            case "favorite", "favorites", "collect" -> 8.0;
            case "comment", "comments" -> 10.0;
            default -> 0.0;
        };
    }
}
