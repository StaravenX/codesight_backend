package com.codesight.profile.config;

import com.codesight.profile.model.AuthorCardStatic;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 创作者名片本地缓存配置
 */
@Configuration
public class ProfileCaffeineConfig {

    @Value("${app.cache.caffeine.author-card.initial-capacity:100}")
    private int initialCapacity;

    @Value("${app.cache.caffeine.author-card.max-size:5000}")
    private long maxSize;

    @Value("${app.cache.caffeine.author-card.ttl-seconds:300}")
    private long ttlSeconds;

    /**
     * 创作者名片本地缓存
     */
    @Bean("authorCardLocalCache")
    public Cache<Long, AuthorCardStatic> authorCardLocalCache() {
        return Caffeine.newBuilder()
                .initialCapacity(initialCapacity)
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
