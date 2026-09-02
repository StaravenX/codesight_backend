package com.codesight.article.config;

import com.codesight.article.model.dto.ArticleDetailStatic;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 文章模块 Caffeine 本地缓存配置
 */
@Configuration
public class ArticleCaffeineConfig {

    @Value("${app.cache.caffeine.article-detail.initial-capacity:100}")
    private int initialCapacity;

    @Value("${app.cache.caffeine.article-detail.max-size:10000}")
    private long maxSize;

    @Value("${app.cache.caffeine.article-detail.ttl-seconds:300}")
    private long ttlSeconds;

    /**
     * 文章详情静态元数据本地缓存
     */
    @Bean("articleDetailLocalCache")
    public Cache<Long, ArticleDetailStatic> articleDetailLocalCache() {
        return Caffeine.newBuilder()
                .initialCapacity(initialCapacity)
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
