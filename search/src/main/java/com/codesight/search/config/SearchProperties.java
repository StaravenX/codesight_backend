package com.codesight.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 搜索业务与算法策略配置
 */
@Data
@ConfigurationProperties(prefix = "codesight.search")
public class SearchProperties {

    /**
     * 索引名称
     */
    private String index;

    /**
     * 向量维度
     */
    private int vectorDims;

    /**
     * 是否开启混合检索
     */
    private boolean hybridEnabled = true;

    /**
     * Embedding 向量化超时时间（毫秒）
     */
    private long embeddingTimeoutMs = 300;
}
