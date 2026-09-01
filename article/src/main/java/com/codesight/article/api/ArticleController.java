package com.codesight.article.api;

import com.codesight.article.api.dto.request.ArticleCreateRequest;
import com.codesight.article.api.dto.request.ArticlePatchRequest;
import com.codesight.article.api.dto.response.ArticleCreateResponse;
import com.codesight.article.api.dto.response.ArticleDetailResponse;
import com.codesight.article.api.dto.response.ArticlePatchResponse;
import com.codesight.article.service.ArticleService;
import com.codesight.common.annotation.CurrentUserId;
import com.codesight.common.annotation.RateLimit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 文章管理控制器
 */
@RestController
@RequestMapping("/api/v1/articles")
@RequiredArgsConstructor
@Validated
@Tag(name = "文章管理接口", description = "提供技术文章草稿保存、公开发布、修改与状态流转接口")
public class ArticleController {

    private final ArticleService articleService;

    /**
     * 创建文章或保存草稿
     *
     * @param request  创建请求参数
     * @param authorId 当前登录用户 ID
     * @return 创建响应（包含生成的分布式雪花 ID 与状态）
     */
    @PostMapping("/create")
    @Operation(summary = "创建文章或草稿", description = "自动解析 Markdown AST 智能提取摘要与字数，可直接发布或保存为草稿")
    @RateLimit(windowSeconds = 60, maxRequests = 10)
    public ArticleCreateResponse createArticle(
            @Valid @RequestBody ArticleCreateRequest request,
            @Parameter(hidden = true) @CurrentUserId Long authorId) {
        return articleService.createArticle(request, authorId);
    }

    /**
     * 修改文章或草稿元数据
     *
     * @param id       目标文章 ID
     * @param request  局部修改参数
     * @param authorId 当前登录用户 ID
     * @return 修改响应（包含文章 ID 与当前状态）
     */
    @PatchMapping("/update/{id}")
    @Operation(summary = "修改文章或草稿", description = "支持文章标题、正文、分类、标签等局部增量更新，以及草稿发布状态流转")
    @RateLimit(windowSeconds = 60, maxRequests = 60)
    public ArticlePatchResponse updateArticle(
            @PathVariable("id") Long id,
            @Valid @RequestBody ArticlePatchRequest request,
            @Parameter(hidden = true) @CurrentUserId Long authorId) {
        return articleService.updateArticle(id, request, authorId);
    }

    /**
     * 获取文章详情
     *
     * @param id 目标文章 ID
     * @return 文章详情
     */
    @GetMapping("/detail/{id}")
    @Operation(summary = "获取文章详情", description = "根据文章 ID 获取文章详情")
    @RateLimit(windowSeconds = 60, maxRequests = 300) 
    public ArticleDetailResponse getDetail(
            @PathVariable("id") Long id,
            @CurrentUserId(required = false) Long userId
            ) {
        return articleService.getDetail(id, userId);
    }
}
