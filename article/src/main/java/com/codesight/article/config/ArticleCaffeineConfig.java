package com.codesight.article.config;

import com.codesight.article.api.dto.response.CategoryResponse;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.model.dto.ArticleDetailStatic;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 文章模块 Caffeine 本地缓存配置
 */
@Configuration
public class ArticleCaffeineConfig {

    @Value("${app.cache.caffeine.article-detail.initial-capacity:100}")
    private int articleInitialCapacity;

    @Value("${app.cache.caffeine.article-detail.max-size:10000}")
    private long articleMaxSize;

    @Value("${app.cache.caffeine.article-detail.ttl-seconds:300}")
    private long articleTtlSeconds;

    @Value("${app.cache.caffeine.category.initial-capacity:10}")
    private int categoryInitialCapacity;

    @Value("${app.cache.caffeine.category.max-size:500}")
    private long categoryMaxSize;

    @Value("${app.cache.caffeine.category.ttl-seconds:600}")
    private long categoryTtlSeconds;

    /**
     * 文章详情静态元数据本地缓存
     */
    @Bean("articleDetailLocalCache")
    public Cache<Long, ArticleDetailStatic> articleDetailLocalCache() {
        return Caffeine.newBuilder()
                .initialCapacity(articleInitialCapacity)
                .maximumSize(articleMaxSize)
                .expireAfterWrite(articleTtlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

    /**
     * 一级技术分类全量本地缓存
     */
    @Bean("categoryLocalCache")
    public Cache<String, List<CategoryResponse>> categoryLocalCache() {
        return Caffeine.newBuilder()
                .initialCapacity(categoryInitialCapacity)
                .maximumSize(categoryMaxSize)
                .expireAfterWrite(categoryTtlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

    /**
     * 分类下二级技术标签列表本地缓存
     */
    @Bean("categoryTagLocalCache")
    public Cache<Long, List<TagResponse>> categoryTagLocalCache() {
        return Caffeine.newBuilder()
                .initialCapacity(categoryInitialCapacity)
                .maximumSize(categoryMaxSize)
                .expireAfterWrite(categoryTtlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
