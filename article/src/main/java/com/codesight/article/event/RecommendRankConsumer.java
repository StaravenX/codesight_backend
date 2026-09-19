package com.codesight.article.event;

import com.codesight.ai.service.ArticleVectorService;
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
 * 推荐流实时打分与用户正向偏好沉淀消费者
 * <p>
 * 职责：
 * 1. 监听计数系统发出的全站用户互动事件流（点赞、阅读、收藏、评论）；
 * 2. 针对文章实体实时推高推荐候选池排序分（ZINCRBY）；
 * 3. 记录文章变更脏标记（Redis Set: counter:dirty:articles），驱动异步定时落盘持久化；
 * 4. 自动将登录用户的正向互动（阅读/点赞/收藏/评论）沉淀至用户动态偏好向量画像。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendRankConsumer {

    public static final String DIRTY_ARTICLES_KEY = "counter:dirty:articles";

    private final RecommendRankService recommendRankService;
    private final StringRedisTemplate redis;
    private final ArticleVectorService articleVectorService;

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
            CounterSchema.ArticleMetric metric = CounterSchema.ArticleMetric.fromCode(event.metric());
            if (metric == null) {
                if (ack != null) {
                    ack.acknowledge();
                }
                return;
            }

            double weight = getMetricWeight(metric);
            double deltaScore = weight * event.delta();

            if (deltaScore != 0.0) {
                // 实时推高推荐候选池分数
                recommendRankService.addOrIncrScore(articleId, deltaScore);

                // 标记实体，便于写聚合落库
                redis.opsForSet().add(DIRTY_ARTICLES_KEY, String.valueOf(articleId));
            }

            // 正向行为（阅读、点赞、收藏、评论）增减量动态同步用户偏好画像
            if (event.userId() > 0 && weight > 0.0) {
                if (event.delta() > 0) {
                    articleVectorService.recordFeedback(
                            ArticleVectorService.FeedbackType.POSITIVE,
                            event.userId(),
                            articleId
                    );
                } else if (event.delta() < 0) {
                    articleVectorService.removeFeedback(
                            ArticleVectorService.FeedbackType.POSITIVE,
                            event.userId(),
                            articleId
                    );
                }
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

    private double getMetricWeight(CounterSchema.ArticleMetric metric) {
        if (metric == null) {
            return 0.0;
        }
        return switch (metric) {
            case VIEWS -> 1.0;
            case LIKE -> 5.0;
            case FAVORITE -> 8.0;
            case COMMENT -> 10.0;
        };
    }
}
