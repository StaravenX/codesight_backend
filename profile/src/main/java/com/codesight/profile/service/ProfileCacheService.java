package com.codesight.profile.service;

import cn.hutool.json.JSONUtil;
import com.codesight.profile.model.AuthorCardStatic;
import com.codesight.user.User;
import com.codesight.user.UserService;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 创作者名片多级缓存服务（L1 Caffeine 本地 + L2 Redis 分布式 + L3 MySQL）
 */
@Service
@RequiredArgsConstructor
public class ProfileCacheService {

    private final Cache<Long, AuthorCardStatic> localCache;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserService userService;

    private static final String REDIS_STATIC_KEY_PREFIX = "author:card:static:";
    private static final String EMPTY_CACHE_FLAG = "{}";
    private static final long EMPTY_CACHE_TTL_MINUTES = 5;

    /**
     * 获取创作者静态名片资料
     *
     * @param authorId 作者用户 ID
     * @return 静态名片 DTO（若作者不存在则返回 null）
     */
    public AuthorCardStatic getStaticCard(Long authorId) {
        if (authorId == null) {
            return null;
        }

        // L1 本地缓存
        AuthorCardStatic l1Dto = localCache.getIfPresent(authorId);
        if (l1Dto != null) {
            return l1Dto;
        }

        // L2 分布式缓存
        String redisKey = REDIS_STATIC_KEY_PREFIX + authorId;
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        if (json != null) {
            if (EMPTY_CACHE_FLAG.equals(json)) {
                return null;
            }
            AuthorCardStatic l2Dto = JSONUtil.toBean(json, AuthorCardStatic.class);
            if (l2Dto != null) {
                localCache.put(authorId, l2Dto);
                return l2Dto;
            }
        }

        // L3 数据库
        User author = userService.getById(authorId);
        if (author == null) {
            stringRedisTemplate.opsForValue().set(redisKey, EMPTY_CACHE_FLAG, EMPTY_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            return null;
        }

        AuthorCardStatic staticDto = new AuthorCardStatic();
        BeanUtils.copyProperties(author, staticDto);

        // 回填 L2 与 L1
        long randomSeconds = ThreadLocalRandom.current().nextLong(1800);
        stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(staticDto), 24 * 3600 + randomSeconds, TimeUnit.SECONDS);
        localCache.put(authorId, staticDto);

        return staticDto;
    }

    /**
     * 淘汰创作者名片缓存（用户修改个人资料/头像时调用）
     *
     * @param authorId 作者用户 ID
     */
    public void evictCache(Long authorId) {
        if (authorId == null) {
            return;
        }
        localCache.invalidate(authorId);
        stringRedisTemplate.delete(REDIS_STATIC_KEY_PREFIX + authorId);
    }
}
