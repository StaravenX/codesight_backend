package com.codesight.article.service;

import com.codesight.article.api.dto.response.CategoryResponse;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.mapper.CategoryMapper;
import com.codesight.article.mapper.CategoryTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.common.cache.MultiLevelCacheTemplate;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryCacheServiceTest {

    @Mock
    private Cache<String, List<CategoryResponse>> categoryLocalCache;

    @Mock
    private Cache<Long, List<TagResponse>> categoryTagLocalCache;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private CategoryTagRelMapper categoryTagRelMapper;

    @Mock
    private MultiLevelCacheTemplate cacheTemplate;

    private CategoryCacheService categoryCacheService;

    @BeforeEach
    void setUp() {
        categoryCacheService = new CategoryCacheService(
                categoryLocalCache,
                categoryTagLocalCache,
                categoryMapper,
                tagMapper,
                categoryTagRelMapper,
                cacheTemplate
        );
    }

    @Test
    @DisplayName("测试查询一级分类列表：委托给 MultiLevelCacheTemplate")
    void testListCategories() {
        CategoryResponse c1 = new CategoryResponse(1L, "后端架构", "backend", 1, "backend.svg");
        when(cacheTemplate.getList(eq(categoryLocalCache), eq("ALL"), eq("category:list:all"), eq(CategoryResponse.class), any()))
                .thenReturn(List.of(c1));

        List<CategoryResponse> result = categoryCacheService.listCategories();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("后端架构", result.getFirst().name());
    }

    @Test
    @DisplayName("测试查询二级标签列表：委托给 MultiLevelCacheTemplate")
    void testListTagsByCategoryId() {
        TagResponse t1 = new TagResponse(10L, "Java", 200L);
        when(cacheTemplate.getList(eq(categoryTagLocalCache), eq(1L), eq("category:tags:1"), eq(TagResponse.class), any()))
                .thenReturn(List.of(t1));

        List<TagResponse> result = categoryCacheService.listTagsByCategoryId(1L);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Java", result.getFirst().name());
    }

    @Test
    @DisplayName("测试主动清理全量分类缓存")
    void testEvictAllCategoryCache() {
        categoryCacheService.evictAllCategoryCache();

        verify(cacheTemplate).evictAll(categoryLocalCache, "category:list:all");
        verify(categoryTagLocalCache).invalidateAll();
    }

    @Test
    @DisplayName("测试主动清理指定分类标签缓存")
    void testEvictTagCache() {
        categoryCacheService.evictTagCache(1L);

        verify(cacheTemplate).evict(categoryTagLocalCache, 1L, "category:tags:1");
    }
}
