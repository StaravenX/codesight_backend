package com.codesight.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class UserCaffeineConfig {

    @Value("${app.cache.caffeine.author-card.initial-capacity:100}")
    private int initialCapacity;

    @Value("${app.cache.caffeine.author-card.max-size:5000}")
    private long maxSize;

    @Value("${app.cache.caffeine.author-card.ttl-seconds:300}")
    private long ttlSeconds;

    @Bean("userBaseLocalCache")
    public Cache<Long, UserBaseInfo> userBaseLocalCache() {
        return Caffeine.newBuilder()
                .initialCapacity(initialCapacity)
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .build();
    }
}
