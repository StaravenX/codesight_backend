package com.codesight.ai.service;

import com.codesight.ai.config.AiProperties;
import com.codesight.ai.util.VectorMath;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 文章向量资产与用户偏好画像服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleVectorService {

    @Getter
    @RequiredArgsConstructor
    public enum FeedbackType {
        POSITIVE("ai:user:positive:", Duration.ofDays(14)),
        NEGATIVE("ai:user:negative:", Duration.ofDays(7));

        private final String keyPrefix;
        private final Duration ttl;

    }
    private static final String KEY_PREFIX_ARTICLE_VECTOR = "ai:article:vector:";
    private static final Duration ARTICLE_VECTOR_TTL = Duration.ofDays(14);
    private static final int MAX_LEAD_CONTENT_LENGTH = 1000;

    private final StringRedisTemplate stringRedisTemplate;
    private final EmbeddingModel embeddingModel;
    private final AiProperties aiProperties;

    /**
     * 记录用户反馈（正向或负向）
     */
    public void recordFeedback(FeedbackType type, Long userId, Long articleId) {
        if (type == null || userId == null || articleId == null) {
            return;
        }

        String key = type.getKeyPrefix() + userId;
        double score = System.currentTimeMillis();

        stringRedisTemplate.opsForZSet().add(key, articleId.toString(), score);

        Long total = stringRedisTemplate.opsForZSet().zCard(key);
        int maxAnchors = getMaxAnchors(type);
        if (total != null && total > maxAnchors) {
            stringRedisTemplate.opsForZSet().removeRange(key, 0, total - maxAnchors - 1);
        }

        stringRedisTemplate.expire(key, type.getTtl().toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 移除用户反馈样本
     */
    public void removeFeedback(FeedbackType type, Long userId, Long articleId) {
        if (type == null || userId == null || articleId == null) {
            return;
        }
        String key = type.getKeyPrefix() + userId;
        stringRedisTemplate.opsForZSet().remove(key, articleId.toString());
    }

    /**
     * 根据文章结构化文本生成并持久化向量资产
     */
    public float[] generateAndSaveVector(Long articleId, String title, String summary, String content) {
        if (articleId == null) {
            return new float[0];
        }

        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append("标题: ").append(title.trim()).append("\n");
        }
        if (summary != null && !summary.isBlank()) {
            sb.append("摘要: ").append(summary.trim()).append("\n");
        }
        if (content != null && !content.isBlank()) {
            String withoutCode = content.replaceAll("```[\\s\\S]*?```", " ");
            String normalized = withoutCode.replaceAll("\\s+", " ").trim();
            if (normalized.length() > MAX_LEAD_CONTENT_LENGTH) {
                normalized = normalized.substring(0, MAX_LEAD_CONTENT_LENGTH);
            }
            if (!normalized.isBlank()) {
                sb.append("核心内容: ").append(normalized);
            }
        }

        String featureText = sb.toString().trim();
        if (featureText.isBlank()) {
            return new float[0];
        }

        float[] vector = embeddingModel.embed(featureText);
        if (vector.length > 0) {
            String key = KEY_PREFIX_ARTICLE_VECTOR + articleId;
            stringRedisTemplate.opsForValue().set(key, serializeVector(vector), ARTICLE_VECTOR_TTL);
        }
        return vector;
    }

    /**
     * 删除指定文章的向量资产
     */
    public void deleteArticleVector(Long articleId) {
        if (articleId == null) {
            return;
        }
        String key = KEY_PREFIX_ARTICLE_VECTOR + articleId;
        stringRedisTemplate.delete(key);
    }

    /**
     * 批量获取文章向量资产
     */
    public Map<Long, float[]> batchGetArticleVector(Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> idList = new ArrayList<>(articleIds);
        List<String> keys = idList.stream()
                .map(id -> KEY_PREFIX_ARTICLE_VECTOR + id)
                .toList();

        List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
        Map<Long, float[]> result = new HashMap<>();

        if (values != null) {
            for (int i = 0; i < idList.size(); i++) {
                String val = values.get(i);
                float[] vector = deserializeVector(val);
                if (vector != null) {
                    result.put(idList.get(i), vector);
                }
            }
        }
        return result;
    }

    /**
     * 获取用户近期反馈样本的文章向量集合
     */
    public List<float[]> batchGetUserFeedbackVector(FeedbackType type, Long userId) {
        if (type == null || userId == null) {
            return Collections.emptyList();
        }

        String key = type.getKeyPrefix() + userId;
        int maxAnchors = getMaxAnchors(type);
        Set<String> memberIds = stringRedisTemplate.opsForZSet().reverseRange(key, 0, maxAnchors - 1);
        if (memberIds == null || memberIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> articleIds = memberIds.stream()
                .map(Long::parseLong)
                .toList();

        Map<Long, float[]> vectorMap = batchGetArticleVector(articleIds);
        return new ArrayList<>(vectorMap.values());
    }

    /**
     * 获取用户动态兴趣向量
     */
    public float[] getUserInterestVector(Long userId) {
        List<float[]> positiveVectors = batchGetUserFeedbackVector(FeedbackType.POSITIVE, userId);
        if (positiveVectors.isEmpty()) {
            return new float[0];
        }
        return VectorMath.aggregateAndNormalize(positiveVectors);
    }

    private int getMaxAnchors(FeedbackType type) {
        return type == FeedbackType.POSITIVE
                ? aiProperties.getMaxPositiveAnchors()
                : aiProperties.getMaxNegativeAnchors();
    }

    private String serializeVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    private float[] deserializeVector(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        String[] parts = str.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}