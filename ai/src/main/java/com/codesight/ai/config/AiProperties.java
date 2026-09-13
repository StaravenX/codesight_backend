package com.codesight.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 业务属性配置
 */
@Data
@ConfigurationProperties(prefix = "codesight.ai")
public class AiProperties {

    /**
     * 是否启用推荐流语义负反馈剪枝
     */
    private boolean pruningEnabled;

    /**
     * 语义负反馈剪枝的余弦相似度阈值
     */
    private double similarityThreshold;

    /**
     * 是否启用推荐流正向向量偏好精排
     */
    private boolean rankingEnabled;

    /**
     * 推荐综合精排中个性化向量相似度权重
     */
    private double similarityWeight;

    /**
     * 推荐综合精排中全局基础分权重
     */
    private double baseScoreWeight;

    /**
     * 用户正向偏好样本最大滑动窗口大小
     */
    private int maxPositiveAnchors;

    /**
     * 用户负反馈样本最大滑动窗口大小
     */
    private int maxNegativeAnchors;
}

