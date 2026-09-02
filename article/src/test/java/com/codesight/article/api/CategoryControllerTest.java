package com.codesight.article.api;

import com.codesight.article.api.dto.response.CategoryResponse;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.service.CategoryService;
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
class CategoryControllerTest {

    @Mock
    private CategoryService categoryService;

    @InjectMocks
    private CategoryController categoryController;

    @Test
    @DisplayName("测试 Controller 查询所有一级分类")
    void testListCategories() {
        CategoryResponse c1 = new CategoryResponse(1L, "后端架构", "backend", 1, "backend.svg");
        when(categoryService.listCategories()).thenReturn(List.of(c1));

        List<CategoryResponse> result = categoryController.listCategories();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("后端架构", result.getFirst().name());
        verify(categoryService).listCategories();
    }

    @Test
    @DisplayName("测试 Controller 查询分类关联的二级标签")
    void testListTagsByCategoryId() {
        TagResponse t1 = new TagResponse(10L, "Java", 200L);
        when(categoryService.listTagsByCategoryId(1L)).thenReturn(List.of(t1));

        List<TagResponse> result = categoryController.listTagsByCategoryId(1L);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Java", result.getFirst().name());
        verify(categoryService).listTagsByCategoryId(1L);
    }
}
