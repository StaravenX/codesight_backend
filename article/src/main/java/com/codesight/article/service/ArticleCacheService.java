package com.codesight.article.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.mapper.ArticleTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.dto.ArticleDetailStatic;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.entity.ArticleTagRel;
import com.codesight.article.model.entity.Tag;
import com.codesight.article.model.enums.ArticleStatus;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 文章静态元数据多级缓存服务（L1 Caffeine 本地 + L2 Redis 分布式 + L3 MySQL 持久化）
 */
@Service
@RequiredArgsConstructor
public class ArticleCacheService {

    private final Cache<Long, ArticleDetailStatic> localCache;
    private final StringRedisTemplate stringRedisTemplate;
    private final ArticleMapper articleMapper;
    private final TagMapper tagMapper;
    private final ArticleTagRelMapper articleTagRelMapper;

    private static final String REDIS_STATIC_KEY_PREFIX = "article:detail:static:";
    private static final String EMPTY_CACHE_FLAG = "{}"; // 空数据缓存标记
    private static final long EMPTY_CACHE_TTL_MINUTES = 5; // 缓存时间

    /**
     * 获取文章静态元数据
     *
     * @param articleId 目标文章 ID
     * @return 静态元数据 DTO（若文章不存在或已删除则返回 null）
     */
    public ArticleDetailStatic getStaticDetail(Long articleId) {
        if (articleId == null) {
            return null;
        }

        // L1 本地缓存
        ArticleDetailStatic l1Dto = localCache.getIfPresent(articleId);
        if (l1Dto != null) {
            return l1Dto;
        }

        // L2 分布式缓存
        String redisKey = REDIS_STATIC_KEY_PREFIX + articleId;
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        if (json != null) {
            if (EMPTY_CACHE_FLAG.equals(json)) {
                return null;
            }
            ArticleDetailStatic l2Dto = JSONUtil.toBean(json, ArticleDetailStatic.class);
            if (l2Dto != null) {
                // 回填 L1 本地缓存
                localCache.put(articleId, l2Dto);
                return l2Dto;
            }
        }

        // L3 数据库
        Article article = articleMapper.selectById(articleId);
        if (article == null || article.getStatus() == ArticleStatus.DELETED) {
            // 缓存空数据
            stringRedisTemplate.opsForValue().set(redisKey, EMPTY_CACHE_FLAG, EMPTY_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            return null;
        }

        ArticleDetailStatic staticDto = new ArticleDetailStatic();
        BeanUtils.copyProperties(article, staticDto);
        staticDto.setTags(getArticleTags(articleId));

        // 回填 L2 与 L1
        // 随机过期时间，避免缓存雪崩
        long randomSeconds = ThreadLocalRandom.current().nextLong(1800);
        stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(staticDto), 24 * 3600 + randomSeconds, TimeUnit.SECONDS);
        localCache.put(articleId, staticDto);

        return staticDto;
    }

    /**
     * 写操作淘汰缓存）
     *
     * @param articleId 目标文章 ID
     */
    public void evictCache(Long articleId) {
        if (articleId == null) {
            return;
        }
        localCache.invalidate(articleId); // 清除 L1 本地缓存
        stringRedisTemplate.delete(REDIS_STATIC_KEY_PREFIX + articleId); // 清除 L2 分布式缓存
    }

    /**
     * 查询文章关联的二级技术标签
     */
    private List<TagResponse> getArticleTags(Long articleId) {
        List<ArticleTagRel> rels = articleTagRelMapper.selectList(
                new LambdaQueryWrapper<ArticleTagRel>().eq(ArticleTagRel::getArticleId, articleId)
        );
        if (rels == null || rels.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> tagIds = rels.stream().map(ArticleTagRel::getTagId).toList();
        List<Tag> tags = tagMapper.selectByIds(tagIds);
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }
        return tags.stream()
                .map(tag -> new TagResponse(tag.getId(), tag.getName()))
                .toList();
    }
}
