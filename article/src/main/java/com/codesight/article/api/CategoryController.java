package com.codesight.article.api;

import com.codesight.article.api.dto.response.CategoryResponse;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.service.CategoryService;
import com.codesight.common.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 技术分类与标签管理接口
 */
@Tag(name = "分类与标签接口", description = "提供全站一级技术分类与二级技术标签的导航查询接口")
@RestController
@RequestMapping("/api/v1/categories")
@Validated
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 获取所有一级技术分类列表
     *
     * @return 一级分类列表
     */
    @Operation(summary = "查询所有一级分类", description = "按权重升序返回全站所有一级技术分类频道")
    @GetMapping
    @RateLimit(windowSeconds = 60, maxRequests = 300)
    public List<CategoryResponse> listCategories() {
        return categoryService.listCategories();
    }

    /**
     * 获取指定一级分类下的所有二级技术标签
     *
     * @param categoryId 一级分类 ID
     * @return 关联的二级标签列表
     */
    @Operation(summary = "查询分类下的二级标签", description = "查询指定一级技术分类所绑定的所有二级技术标签列表")
    @GetMapping("/{categoryId}/tags")
    @RateLimit(windowSeconds = 60, maxRequests = 300)
    public List<TagResponse> listTagsByCategoryId(
            @Parameter(description = "一级技术分类 ID", required = true)
            @PathVariable("categoryId") Long categoryId) {
        return categoryService.listTagsByCategoryId(categoryId);
    }
}
