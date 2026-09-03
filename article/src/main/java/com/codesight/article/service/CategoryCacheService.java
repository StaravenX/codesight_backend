package com.codesight.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.article.api.dto.response.CategoryResponse;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.mapper.CategoryMapper;
import com.codesight.article.mapper.CategoryTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.entity.Category;
import com.codesight.article.model.entity.CategoryTagRel;
import com.codesight.article.model.entity.Tag;
import com.codesight.common.cache.MultiLevelCacheTemplate;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 分类与标签多级缓存服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryCacheService {

    private final Cache<String, List<CategoryResponse>> categoryLocalCache;
    private final Cache<Long, List<TagResponse>> categoryTagLocalCache;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final CategoryTagRelMapper categoryTagRelMapper;
    private final MultiLevelCacheTemplate cacheTemplate;

    private static final String CACHE_KEY_CATEGORIES_ALL = "ALL";
    private static final String REDIS_KEY_CATEGORIES_ALL = "category:list:all";
    private static final String REDIS_KEY_CATEGORY_TAGS_PREFIX = "category:tags:";

    /**
     * 查询所有一级分类（多级缓存）
     */
    public List<CategoryResponse> listCategories() {
        return cacheTemplate.getList(
                categoryLocalCache,
                CACHE_KEY_CATEGORIES_ALL,
                REDIS_KEY_CATEGORIES_ALL,
                CategoryResponse.class,
                () -> {
                    List<Category> categories = categoryMapper.selectList(
                            new LambdaQueryWrapper<Category>()
                                    .orderByAsc(Category::getSortOrder)
                                    .orderByAsc(Category::getId)
                    );
                    if (categories == null || categories.isEmpty()) {
                        return Collections.emptyList();
                    }
                    return categories.stream()
                            .map(c -> new CategoryResponse(
                                    c.getId(),
                                    c.getName(),
                                    c.getSlug(),
                                    c.getSortOrder(),
                                    c.getIconUrl()
                            ))
                            .toList();
                }
        );
    }

    /**
     * 查询指定分类下的二级标签列表（多级缓存）
     */
    public List<TagResponse> listTagsByCategoryId(Long categoryId) {
        if (categoryId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类 ID 不能为空");
        }

        return cacheTemplate.getList(
                categoryTagLocalCache,
                categoryId,
                REDIS_KEY_CATEGORY_TAGS_PREFIX + categoryId,
                TagResponse.class,
                () -> {
                    Category category = categoryMapper.selectById(categoryId);
                    if (category == null) {
                        throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "指定的分类不存在");
                    }

                    List<CategoryTagRel> rels = categoryTagRelMapper.selectList(
                            new LambdaQueryWrapper<CategoryTagRel>()
                                    .eq(CategoryTagRel::getCategoryId, categoryId)
                    );
                    if (rels == null || rels.isEmpty()) {
                        return Collections.emptyList();
                    }

                    List<Long> tagIds = rels.stream().map(CategoryTagRel::getTagId).toList();
                    List<Tag> tags = tagMapper.selectByIds(tagIds);
                    if (tags == null || tags.isEmpty()) {
                        return Collections.emptyList();
                    }

                    return tags.stream()
                            .map(t -> new TagResponse(
                                    t.getId(),
                                    t.getName(),
                                    t.getArticleCount()
                            ))
                            .toList();
                }
        );
    }

    /**
     * 主动清除全量分类与标签缓存
     */
    public void evictAllCategoryCache() {
        cacheTemplate.evictAll(categoryLocalCache, REDIS_KEY_CATEGORIES_ALL);
        if (categoryTagLocalCache != null) {
            categoryTagLocalCache.invalidateAll();
        }
    }

    /**
     * 主动清除指定分类下的标签缓存
     */
    public void evictTagCache(Long categoryId) {
        if (categoryId != null) {
            cacheTemplate.evict(categoryTagLocalCache, categoryId, REDIS_KEY_CATEGORY_TAGS_PREFIX + categoryId);
        }
    }
}
