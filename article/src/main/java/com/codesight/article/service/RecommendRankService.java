package com.codesight.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 综合推荐流候选池服务（基于 Redis ZSET + Lua 原子降温）
 * <p>
 * 职责：
 * 1. 维护高频推荐候选池（Top-3000 活跃文章）；
 * 2. 接收 Kafka 互动事件驱动实时推高分数（ZINCRBY）；
 * 3. 定时降温（Lua 原子衰减）；
 * 4. 为异步批量持久化回写 MySQL 提供实时排序分数据源。
 */
@Slf4j
@Service
public class RecommendRankService {

    public static final String RECOMMEND_POOL_KEY = "feed:recommend:pool";
    public static final int DEFAULT_MAX_CAPACITY = 3000;
    public static final int DEFAULT_MIN_CAPACITY = 200;
    public static final double DEFAULT_DECAY_FACTOR = 0.9;
    public static final double BASE_INITIAL_SCORE = 10.0;
    public static final double MIN_SCORE_THRESHOLD = 1.0;
    public static final int WARMUP_MIN_THRESHOLD = 100;
    public static final int REFILL_ALERT_THRESHOLD = 500;

    private final StringRedisTemplate redis;
    private final ArticleMapper articleMapper;
    private final RedisScript<Long> decayScript;

    public RecommendRankService(StringRedisTemplate redis, ArticleMapper articleMapper) {
        this.redis = redis;
        this.articleMapper = articleMapper;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/decay_script.lua"));
        this.decayScript = script;
    }

    /**
     * 原子变更文章在推荐池中的排序分（若不存在则赋予底分）
     *
     * @param articleId 文章全局唯一 ID
     * @param delta     本次互动权重变量
     */
    public void addOrIncrScore(Long articleId, double delta) {
        if (articleId == null) {
            return;
        }
        String member = String.valueOf(articleId);
        try {
            Double currentScore = redis.opsForZSet().score(RECOMMEND_POOL_KEY, member);
            // 文章不在推荐池中
            if (currentScore == null) {
                // 过滤负向互动
                if (delta < 0) {
                    return;
                }
                // 首次入池或沉寂唤醒：回查历史真实分值
                double baseScore = BASE_INITIAL_SCORE;
                Article article = articleMapper.selectById(articleId);
                if (article == null || article.getStatus() != ArticleStatus.PUBLISHED) {
                    return;
                }
                if (article.getRankScore() != null && article.getRankScore() > BASE_INITIAL_SCORE) {
                    baseScore = article.getRankScore();
                }
                redis.opsForZSet().add(RECOMMEND_POOL_KEY, member, baseScore + delta);
            } else {
                Double newScore = redis.opsForZSet().incrementScore(RECOMMEND_POOL_KEY, member, delta);
                if (newScore != null && newScore < MIN_SCORE_THRESHOLD) {
                    redis.opsForZSet().remove(RECOMMEND_POOL_KEY, member);
                }
            }
        } catch (Exception e) {
            log.warn("更新推荐候选池分数失败, articleId={}, delta={}", articleId, delta, e);
        }
    }

    /**
     * 从推荐候选池中分页拉取文章 ID 与对应排序分
     *
     * @param cursorRankScore 游标排序分（首屏为 null）
     * @param cursorArticleId 游标文章 ID（首屏为 null）
     * @param limitSize       拉取数量（通常为 size + 1）
     * @return 文章 ID 及其对应排序分列表（按分数倒序排列）
     */
    public List<TypedTuple<String>> getRankedArticleIds(Double cursorRankScore, Long cursorArticleId, int limitSize) {
        if (limitSize <= 0) {
            return Collections.emptyList();
        }
        try {

            if (cursorArticleId == null) {
                Set<TypedTuple<String>> tuples = redis.opsForZSet()
                        .reverseRangeWithScores(RECOMMEND_POOL_KEY, 0, limitSize - 1);
                return (tuples == null || tuples.isEmpty()) ? Collections.emptyList() : new ArrayList<>(tuples);
            }

            // 基于文章 ID 获取其在 ZSet 中的绝对排位
            String member = String.valueOf(cursorArticleId);
            Long rank = redis.opsForZSet().reverseRank(RECOMMEND_POOL_KEY, member);
            if (rank != null) {
                Set<TypedTuple<String>> tuples = redis.opsForZSet()
                        .reverseRangeWithScores(RECOMMEND_POOL_KEY, rank + 1, rank + limitSize);
                return (tuples == null || tuples.isEmpty()) ? Collections.emptyList() : new ArrayList<>(tuples);
            }

            // ZSet中未找到相应文章ID
            double max = (cursorRankScore != null) ? cursorRankScore : Double.POSITIVE_INFINITY;
            Set<TypedTuple<String>> fallbackTuples = redis.opsForZSet()
                    .reverseRangeByScoreWithScores(RECOMMEND_POOL_KEY, 0.0, max, 0, limitSize);
            return (fallbackTuples == null || fallbackTuples.isEmpty()) ? Collections.emptyList() : new ArrayList<>(fallbackTuples);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }


    /**
     * 文章批量降温
     *
     * @param factor      衰减系数（如 0.9）
     * @param maxCapacity 最大保留文章数（如 3000）
     */
    public void decayAll(double factor, int maxCapacity) {
        redis.execute(
                decayScript,
                List.of(RECOMMEND_POOL_KEY),
                String.valueOf(factor),
                String.valueOf(maxCapacity),
                String.valueOf(DEFAULT_MIN_CAPACITY)
        );
    }

    /**
     * 获取指定文章在推荐池中的排序分
     */
    public Double getScore(Long articleId) {
        if (articleId == null) {
            return null;
        }
        return redis.opsForZSet().score(RECOMMEND_POOL_KEY, String.valueOf(articleId));
    }

    /**
     * 批量读取指定一批文章在推荐池中的排序分
     */
    public Map<Long, Double> batchGetScores(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Double> result = new HashMap<>(articleIds.size());
        for (Long id : articleIds) {
            Double s = getScore(id);
            if (s != null) {
                result.put(id, s);
            }
        }
        return result;
    }

    /**
     * 批量回填文章到推荐候选池
     *
     * @param articles 待回填的文章实体列表
     */
    public void batchAddScores(List<com.codesight.article.model.entity.Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }
        try {
            Set<TypedTuple<String>> tuples = new HashSet<>(articles.size());
            for (com.codesight.article.model.entity.Article a : articles) {
                if (a != null && a.getId() != null) {
                    double score = (a.getRankScore() != null && a.getRankScore() > 0.0)
                            ? a.getRankScore()
                            : BASE_INITIAL_SCORE;
                    tuples.add(TypedTuple.of(String.valueOf(a.getId()), score));
                }
            }
            if (!tuples.isEmpty()) {
                redis.opsForZSet().add(RECOMMEND_POOL_KEY, tuples);
                log.info("成功回填 {} 篇文章至推荐候选池", tuples.size());
            }
        } catch (Exception e) {
            log.warn("批量回填推荐候选池失败", e);
        }
    }

    /**
     * 若推荐候选池容量不足，从数据库获取优质历史文章
     *
     * @param threshold 触发回灌的容量下限警戒线
     * @param limitSize 目标注满容量（如 3000）
     * @return 是否触发了回灌
     */
    public boolean refillPoolIfLow(long threshold, int limitSize) {
        try {
            Long currentSize = redis.opsForZSet().zCard(RECOMMEND_POOL_KEY);
            if (currentSize != null && currentSize >= threshold) {
                return false;
            }
            List<Article> topArticles = articleMapper.selectList(
                    new LambdaQueryWrapper<Article>()
                            .eq(Article::getStatus, ArticleStatus.PUBLISHED)
                            .eq(Article::getVisible, ArticleVisible.PUBLIC)
                            .orderByDesc(Article::getRankScore)
                            .orderByDesc(Article::getId)
                            .last("LIMIT " + limitSize)
            );
            batchAddScores(topArticles);
            return true;
        } catch (Exception e) {
            log.error("推荐候选池自愈回灌异常", e);
            return false;
        }
    }

    /**
     * 从推荐候选池中移除指定文章（用于下架或删除）
     *
     * @param articleId 文章 ID
     */
    public void removeArticle(Long articleId) {
        if (articleId == null) {
            return;
        }
        try {
            redis.opsForZSet().remove(RECOMMEND_POOL_KEY, String.valueOf(articleId));
        } catch (Exception e) {
            log.warn("从推荐候选池移除文章失败, articleId={}", articleId, e);
        }
    }
}