package com.codesight.article.service;

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
import com.codesight.common.cache.MultiLevelCacheTemplate;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 文章静态元数据多级缓存服务
 */
@Service
@RequiredArgsConstructor
public class ArticleCacheService {

    private final Cache<Long, ArticleDetailStatic> localCache;
    private final ArticleMapper articleMapper;
    private final TagMapper tagMapper;
    private final ArticleTagRelMapper articleTagRelMapper;
    private final MultiLevelCacheTemplate cacheTemplate;

    private static final String REDIS_STATIC_KEY_PREFIX = "article:detail:static:";

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

        return cacheTemplate.get(
                localCache,
                articleId,
                REDIS_STATIC_KEY_PREFIX + articleId,
                ArticleDetailStatic.class,
                () -> {
                    Article article = articleMapper.selectById(articleId);
                    if (article == null || article.getStatus() == ArticleStatus.DELETED) {
                        return null;
                    }

                    ArticleDetailStatic staticDto = new ArticleDetailStatic();
                    BeanUtils.copyProperties(article, staticDto);
                    staticDto.setTags(getArticleTags(articleId));
                    return staticDto;
                }
        );
    }

    /**
     * 写操作淘汰缓存
     *
     * @param articleId 目标文章 ID
     */
    public void evictCache(Long articleId) {
        if (articleId == null) {
            return;
        }
        cacheTemplate.evict(localCache, articleId, REDIS_STATIC_KEY_PREFIX + articleId);
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
