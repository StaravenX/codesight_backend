package com.codesight.article.task;

import com.codesight.article.event.RecommendRankConsumer;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.service.RecommendRankService;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 文章计数与综合推荐分异步落盘任务
 * <p>
 * 职责：
 * 1. 定时从 Redis 脏标记池（counter:dirty:articles）中弹出近期发生变动的文章 ID；
 * 2. 单次 MGET 批量拉取 16 字节 SDS 中的实时互动计数（阅读、点赞、评论、收藏）以及 ZSET 排序分；
 * 3. 批量持久化回写 MySQL articles 表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleCounterPersistenceTask {

    private static final int BATCH_SIZE = 200;

    private final StringRedisTemplate redis;
    private final CounterService counterService;
    private final RecommendRankService recommendRankService;
    private final ArticleMapper articleMapper;

    @Scheduled(initialDelay = 30_000L, fixedRate = 60_000L)
    public void flushDirtyArticlesToDb() {
        List<String> failedIds = new ArrayList<>();
        try {
            List<String> popped = redis.opsForSet().pop(RecommendRankConsumer.DIRTY_ARTICLES_KEY, BATCH_SIZE);
            if (popped == null || popped.isEmpty()) {
                return;
            }

            List<Long> articleIds = popped.stream()
                    .map(Long::parseLong)
                    .toList();

            Map<String, Map<CounterSchema.MetricItem, Long>> countsMap = counterService.batchGetCounts(
                    CounterSchema.EntityType.ARTICLE,
                    popped
            );

            Map<Long, Double> scoreMap = recommendRankService.batchGetScores(articleIds);

            for (Long articleId : articleIds) {
                try {
                    Map<CounterSchema.MetricItem, Long> counts = countsMap.getOrDefault(String.valueOf(articleId), Collections.emptyMap());
                    Double rankScore = scoreMap.get(articleId);

                    Article updateEntity = new Article();
                    updateEntity.setId(articleId);

                    boolean hasData = false;
                    if (counts.containsKey(CounterSchema.ArticleMetric.VIEWS)) {
                        updateEntity.setViewCount(counts.get(CounterSchema.ArticleMetric.VIEWS));
                        hasData = true;
                    }
                    if (counts.containsKey(CounterSchema.ArticleMetric.LIKE)) {
                        updateEntity.setLikeCount(counts.get(CounterSchema.ArticleMetric.LIKE));
                        hasData = true;
                    }
                    if (counts.containsKey(CounterSchema.ArticleMetric.COMMENT)) {
                        updateEntity.setCommentCount(counts.get(CounterSchema.ArticleMetric.COMMENT));
                        hasData = true;
                    }
                    if (counts.containsKey(CounterSchema.ArticleMetric.FAVORITE)) {
                        updateEntity.setFavoriteCount(counts.get(CounterSchema.ArticleMetric.FAVORITE));
                        hasData = true;
                    }
                    if (rankScore != null) {
                        updateEntity.setRankScore(rankScore);
                        hasData = true;
                    }

                    if (hasData) {
                        articleMapper.updateById(updateEntity);
                    }
                } catch (Exception e) {
                    failedIds.add(String.valueOf(articleId));
                }
            }
        } catch (Exception e) {
            log.error("执行文章计数与推荐分异步落库异常", e);
        }
        if (!failedIds.isEmpty()) {
            redis.opsForSet().add(RecommendRankConsumer.DIRTY_ARTICLES_KEY, failedIds.toArray(new String[0]));
        }
    }
}
