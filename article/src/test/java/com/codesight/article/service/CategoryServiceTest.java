package com.codesight.article.service;

import com.codesight.article.api.dto.response.CategoryResponse;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.mapper.CategoryMapper;
import com.codesight.article.mapper.CategoryTagRelMapper;
import com.codesight.article.mapper.TagMapper;
import com.codesight.article.model.entity.Category;
import com.codesight.article.model.entity.CategoryTagRel;
import com.codesight.article.model.entity.Tag;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private CategoryTagRelMapper categoryTagRelMapper;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    @DisplayName("测试查询所有一级分类：正常返回并包含完整元数据")
    void testListCategories_Success() {
        Category c1 = Category.builder()
                .id(1L)
                .name("后端架构")
                .slug("backend")
                .sortOrder(1)
                .iconUrl("https://oss.codesight.cn/icons/backend.svg")
                .build();
        Category c2 = Category.builder()
                .id(2L)
                .name("前端工程")
                .slug("frontend")
                .sortOrder(2)
                .iconUrl("https://oss.codesight.cn/icons/frontend.svg")
                .build();

        when(categoryMapper.selectList(any())).thenReturn(List.of(c1, c2));

        List<CategoryResponse> result = categoryService.listCategories();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(1L, result.getFirst().id());
        assertEquals("后端架构", result.getFirst().name());
        assertEquals("backend", result.getFirst().slug());
        assertEquals(1, result.get(0).sortOrder());
        assertEquals("https://oss.codesight.cn/icons/backend.svg", result.get(0).iconUrl());

        assertEquals(2L, result.get(1).id());
        assertEquals("前端工程", result.get(1).name());
    }

    @Test
    @DisplayName("测试查询一级分类列表：数据库为空返回空列表")
    void testListCategories_Empty() {
        when(categoryMapper.selectList(any())).thenReturn(List.of());

        List<CategoryResponse> result = categoryService.listCategories();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("测试查询指定分类下的二级标签：正常关联并返回")
    void testListTagsByCategoryId_Success() {
        Category category = Category.builder().id(1L).name("后端架构").slug("backend").build();
        when(categoryMapper.selectById(1L)).thenReturn(category);

        CategoryTagRel rel1 = CategoryTagRel.builder().id(101L).categoryId(1L).tagId(10L).build();
        CategoryTagRel rel2 = CategoryTagRel.builder().id(102L).categoryId(1L).tagId(20L).build();
        when(categoryTagRelMapper.selectList(any())).thenReturn(List.of(rel1, rel2));

        Tag t1 = Tag.builder().id(10L).name("Java").articleCount(350L).build();
        Tag t2 = Tag.builder().id(20L).name("Docker").articleCount(120L).build();
        when(tagMapper.selectByIds(List.of(10L, 20L))).thenReturn(List.of(t1, t2));

        List<TagResponse> tags = categoryService.listTagsByCategoryId(1L);

        assertNotNull(tags);
        assertEquals(2, tags.size());
        assertEquals(10L, tags.getFirst().id());
        assertEquals("Java", tags.get(0).name());
        assertEquals(350L, tags.get(0).articleCount());

        assertEquals(20L, tags.get(1).id());
        assertEquals("Docker", tags.get(1).name());
    }

    @Test
    @DisplayName("测试查询二级标签：分类不存在抛出 CATEGORY_NOT_FOUND")
    void testListTagsByCategoryId_CategoryNotFound() {
        when(categoryMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> categoryService.listTagsByCategoryId(999L));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("测试查询二级标签：分类下无绑定标签返回空列表")
    void testListTagsByCategoryId_NoTags() {
        Category category = Category.builder().id(1L).name("后端架构").slug("backend").build();
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(categoryTagRelMapper.selectList(any())).thenReturn(List.of());

        List<TagResponse> tags = categoryService.listTagsByCategoryId(1L);

        assertNotNull(tags);
        assertTrue(tags.isEmpty());
        verify(tagMapper, never()).selectByIds(any());
    }
}
