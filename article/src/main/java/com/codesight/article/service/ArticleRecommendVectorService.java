package com.codesight.article.service;

import com.codesight.ai.config.AiProperties;
import com.codesight.ai.service.ArticleVectorService;
import com.codesight.ai.util.VectorMath;
import com.codesight.article.model.entity.Article;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 推荐系统双向向量感知服务（负向语义剪枝 + 正向偏好精排）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleRecommendVectorService {

    private static final double SIMILARITY_SCORE_SCALE = 100.0;

    private final ArticleVectorService articleVectorService;
    private final AiProperties aiProperties;

    /**
     * 推荐流双向向量感知推荐（负向语义剪枝 + 正向偏好精排）
     *
     * @param userId     当前用户 ID（未登录为 null）
     * @param candidates 候选文章列表
     * @return 经过剪枝与加权精排后的文章列表
     */
    public List<Article> recommendAndRerank(Long userId, List<Article> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 未登录或未开启推荐特性，直接原样返回
        boolean pruningEnabled = aiProperties.isPruningEnabled() && userId != null;
        boolean rankingEnabled = aiProperties.isRankingEnabled() && userId != null;
        if (!pruningEnabled && !rankingEnabled) {
            return candidates;
        }

        // 获取用户负向锚点与正向兴趣向量
        List<float[]> negativeAnchors = pruningEnabled
                ? articleVectorService.batchGetUserFeedbackVector(ArticleVectorService.FeedbackType.NEGATIVE, userId)
                : Collections.emptyList();
        float[] userInterestVector = rankingEnabled
                ? articleVectorService.getUserInterestVector(userId)
                : null;

        boolean hasNegativeAnchors = !negativeAnchors.isEmpty();
        boolean hasUserInterest = userInterestVector != null && userInterestVector.length > 0;

        // 若用户无任何正负向画像数据，直接返回
        if (!hasNegativeAnchors && !hasUserInterest) {
            return candidates;
        }

        // 提取候选文章 ID，单次批量获取向量
        List<Long> candidateIds = candidates.stream()
                .map(Article::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, float[]> candidateVectors = articleVectorService.batchGetArticleVector(candidateIds);
        double threshold = aiProperties.getSimilarityThreshold();

        // 负向语义剪枝
        List<Article> retainedCandidates;
        if (hasNegativeAnchors) {
            retainedCandidates = new ArrayList<>(candidates.size());
            for (Article candidate : candidates) {
                float[] vector = candidateVectors.get(candidate.getId());
                if (vector == null || vector.length == 0) {
                    retainedCandidates.add(candidate);
                    continue;
                }

                boolean isPruned = false;
                for (float[] anchor : negativeAnchors) {
                    if (VectorMath.cosineSimilarity(vector, anchor) >= threshold) {
                        isPruned = true;
                        break;
                    }
                }

                if (!isPruned) {
                    retainedCandidates.add(candidate);
                }
            }
        } else {
            retainedCandidates = candidates;
        }

        // 剪枝后若为空、仅剩1篇或无正向兴趣画像，直接返回
        if (retainedCandidates.isEmpty() || retainedCandidates.size() == 1 || !hasUserInterest) {
            return retainedCandidates;
        }

        // 正向动态偏好加权精排
        double alpha = aiProperties.getSimilarityWeight();
        double beta = aiProperties.getBaseScoreWeight();

        record ScoredItem(Article item, double compositeScore) {}

        List<ScoredItem> scoredItems = new ArrayList<>(retainedCandidates.size());
        for (Article candidate : retainedCandidates) {
            double baseScore = candidate.getRankScore() != null ? candidate.getRankScore() : 0.0;
            float[] articleVector = candidateVectors.get(candidate.getId());

            double normSim = 0.5;
            if (articleVector != null && articleVector.length > 0) {
                double rawSim = VectorMath.cosineSimilarity(userInterestVector, articleVector);
                normSim = (rawSim + 1.0) / 2.0;
            }

            double compositeScore = alpha * (normSim * SIMILARITY_SCORE_SCALE) + beta * baseScore;
            scoredItems.add(new ScoredItem(candidate, compositeScore));
        }

        scoredItems.sort((a, b) -> Double.compare(b.compositeScore(), a.compositeScore()));

        return scoredItems.stream().map(ScoredItem::item).toList();
    }
}
