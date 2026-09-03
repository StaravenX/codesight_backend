package com.codesight.article.service;

import com.codesight.article.api.dto.response.CategoryResponse;
import com.codesight.article.api.dto.response.TagResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryCacheService categoryCacheService;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    @DisplayName("测试查询所有一级分类")
    void testListCategories() {
        CategoryResponse c1 = new CategoryResponse(1L, "后端架构", "backend", 1, "https://oss.codesight.cn/icons/backend.svg");
        when(categoryCacheService.listCategories()).thenReturn(List.of(c1));

        List<CategoryResponse> result = categoryService.listCategories();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("后端架构", result.getFirst().name());
        verify(categoryCacheService).listCategories();
    }

    @Test
    @DisplayName("测试查询分类关联的二级标签")
    void testListTagsByCategoryId() {
        TagResponse t1 = new TagResponse(10L, "Java", 350L);
        when(categoryCacheService.listTagsByCategoryId(1L)).thenReturn(List.of(t1));

        List<TagResponse> tags = categoryService.listTagsByCategoryId(1L);

        assertNotNull(tags);
        assertEquals(1, tags.size());
        assertEquals("Java", tags.getFirst().name());
        verify(categoryCacheService).listTagsByCategoryId(1L);
    }
}
