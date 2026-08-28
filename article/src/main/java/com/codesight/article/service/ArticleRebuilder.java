package com.codesight.article.service;

import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.counter.schema.CounterRebuilder;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 文章维度计数自愈重建策略（实现计数中台 SPI 接口）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleRebuilder implements CounterRebuilder {

    private final CounterService counterService;
    private final ArticleMapper articleMapper;

    @Override
    public CounterSchema.EntityType entityType() {
        return CounterSchema.EntityType.ARTICLE;
    }

    @Override
    public Map<CounterSchema.MetricItem, Long> rebuild(String entityId) {
        Map<CounterSchema.MetricItem, Long> resultMap = new HashMap<>();
        Article article = articleMapper.selectById(Long.parseLong(entityId));

        if (article == null) {
            log.error("文章articleId={}数据丢失, 需要触发 Kafka 事件溯源灾难回放流程！", entityId);
        }

        // 1. 阅读量：从 MySQL 主表归档快照恢复
        Long viewCount = (article != null && article.getViewCount() != null) ? article.getViewCount() : 0L;

        // 2. 点赞量：优先从 4KB 分片位图统计真值；若位图缺失(-1)，降级从 MySQL 存盘快照兜底
        long bitmapLikes = counterService.bitCountShards(
                this.entityType(),
                entityId,
                CounterSchema.ArticleMetric.LIKE
        );
        if (bitmapLikes < 0) {
            log.warn("Redis 点赞位图缺失，启动 MySQL 存盘兜底: articleId={}", entityId);
        }
        Long likeCount = (bitmapLikes >= 0) 
                ? bitmapLikes 
                : ((article != null && article.getLikeCount() != null) ? article.getLikeCount() : 0L);

        // 3. 评论量：从 MySQL 存盘快照恢复 todo: 评论模块完善后补充
        Long commentCount = (article != null && article.getCommentCount() != null) ? article.getCommentCount() : 0L;

        // 4. 收藏量：优先从 4KB 分片位图统计真值；若位图缺失(-1)，降级从 MySQL 存盘快照兜底
        long bitmapFavorites = counterService.bitCountShards(
                this.entityType(),
                entityId,
                CounterSchema.ArticleMetric.FAVORITE
        );
        if (bitmapFavorites < 0) {
            log.warn("Redis 收藏位图缺失，启动 MySQL 存盘兜底: articleId={}", entityId);
        }
        Long favoriteCount = (bitmapFavorites >= 0) 
                ? bitmapFavorites 
                : ((article != null && article.getFavoriteCount() != null) ? article.getFavoriteCount() : 0L);

        // 5. 强类型装填 4 维指标快照
        resultMap.put(CounterSchema.ArticleMetric.VIEWS, viewCount);
        resultMap.put(CounterSchema.ArticleMetric.LIKE, likeCount);
        resultMap.put(CounterSchema.ArticleMetric.COMMENT, commentCount);
        resultMap.put(CounterSchema.ArticleMetric.FAVORITE, favoriteCount);

        return resultMap;
    }
}
