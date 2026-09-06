package com.codesight.article.service;

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
    public static final double DEFAULT_DECAY_FACTOR = 0.9;
    public static final double BASE_INITIAL_SCORE = 10.0;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> decayScript;

    public RecommendRankService(StringRedisTemplate redis) {
        this.redis = redis;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/decay_script.lua"));
        this.decayScript = script;
    }

    /**
     * 原子推高文章在推荐池中的排序分（若不存在则赋予底分）
     *
     * @param articleId 文章全局唯一 ID
     * @param delta     本次互动权重增量
     */
    public void addOrIncrScore(Long articleId, double delta) {
        if (articleId == null) {
            return;
        }
        String member = String.valueOf(articleId);
        try {
            Double currentScore = redis.opsForZSet().score(RECOMMEND_POOL_KEY, member);
            if (currentScore == null) {
                // 首次入池，赋予基础起跑分 + 增量分
                redis.opsForZSet().add(RECOMMEND_POOL_KEY, member, BASE_INITIAL_SCORE + delta);
            } else {
                redis.opsForZSet().incrementScore(RECOMMEND_POOL_KEY, member, delta);
            }
        } catch (Exception e) {
            log.warn("更新推荐候选池分数失败, articleId={}, delta={}", articleId, delta, e);
        }
    }

    /**
     * 从推荐候选池中分页拉取文章 ID 与对应排序分
     *
     * @param cursorRankScore 游标排序分（首屏为 null）
     * @param limitSize       拉取数量（通常为 size + 1）
     * @return 文章 ID 及其对应排序分列表（按分数倒序排列）
     */
    public List<TypedTuple<String>> getRankedArticleIds(Double cursorRankScore, int limitSize) {
        try {
            double max = (cursorRankScore != null) ? (cursorRankScore - 0.00001) : Double.POSITIVE_INFINITY;
            double min = 0.0;

            Set<TypedTuple<String>> tuples = redis.opsForZSet()
                    .reverseRangeByScoreWithScores(RECOMMEND_POOL_KEY, min, max, 0, limitSize);

            if (tuples == null || tuples.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(tuples);
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
                String.valueOf(maxCapacity)
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