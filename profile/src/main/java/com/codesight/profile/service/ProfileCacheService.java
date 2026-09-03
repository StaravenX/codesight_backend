package com.codesight.profile.service;

import com.codesight.common.cache.MultiLevelCacheTemplate;
import com.codesight.profile.model.AuthorCardStatic;
import com.codesight.user.User;
import com.codesight.user.UserService;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * 创作者名片多级缓存服务
 */
@Service
@RequiredArgsConstructor
public class ProfileCacheService {

    private final Cache<Long, AuthorCardStatic> localCache;
    private final UserService userService;
    private final MultiLevelCacheTemplate cacheTemplate;

    private static final String REDIS_STATIC_KEY_PREFIX = "author:card:static:";

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

        return cacheTemplate.get(
                localCache,
                authorId,
                REDIS_STATIC_KEY_PREFIX + authorId,
                AuthorCardStatic.class,
                () -> {
                    User author = userService.getById(authorId);
                    if (author == null) {
                        return null;
                    }
                    AuthorCardStatic staticDto = new AuthorCardStatic();
                    BeanUtils.copyProperties(author, staticDto);
                    return staticDto;
                }
        );
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
        cacheTemplate.evict(localCache, authorId, REDIS_STATIC_KEY_PREFIX + authorId);
    }
}
