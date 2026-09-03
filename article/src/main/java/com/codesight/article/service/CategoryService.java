package com.codesight.article.service;

import com.codesight.article.api.dto.response.CategoryResponse;
import com.codesight.article.api.dto.response.TagResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分类与标签业务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryCacheService categoryCacheService;

    /**
     * 查询所有一级技术分类（多级缓存）
     *
     * @return 一级分类列表
     */
    public List<CategoryResponse> listCategories() {
        return categoryCacheService.listCategories();
    }

    /**
     * 查询指定一级分类下的所有二级技术标签（多级缓存）
     *
     * @param categoryId 一级技术分类 ID
     * @return 该分类下的二级标签列表
     */
    public List<TagResponse> listTagsByCategoryId(Long categoryId) {
        return categoryCacheService.listTagsByCategoryId(categoryId);
    }
}
