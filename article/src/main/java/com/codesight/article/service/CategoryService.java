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
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 分类与标签业务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final CategoryTagRelMapper categoryTagRelMapper;

    /**
     * 查询所有一级技术分类（按 sortOrder 升序、id 升序排序）
     *
     * @return 一级分类列表
     */
    public List<CategoryResponse> listCategories() {
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

    /**
     * 查询指定一级分类下的所有二级技术标签
     *
     * @param categoryId 一级技术分类 ID
     * @return 该分类下的二级标签列表
     */
    public List<TagResponse> listTagsByCategoryId(Long categoryId) {
        if (categoryId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类 ID 不能为空");
        }

        // 1. 校验分类是否存在
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "指定的分类不存在");
        }

        // 2. 查询分类关联的标签 ID 列表
        List<CategoryTagRel> rels = categoryTagRelMapper.selectList(
                new LambdaQueryWrapper<CategoryTagRel>()
                        .eq(CategoryTagRel::getCategoryId, categoryId)
        );
        if (rels == null || rels.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> tagIds = rels.stream().map(CategoryTagRel::getTagId).toList();

        // 3. 批量查询标签实体并组装响应
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
}
