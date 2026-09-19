package com.codesight.article.api;

import com.codesight.article.api.dto.request.ArticleCreateRequest;
import com.codesight.article.api.dto.request.ArticleFeedRequest;
import com.codesight.article.api.dto.request.ArticlePatchRequest;
import com.codesight.article.api.dto.response.ArticleCreateResponse;
import com.codesight.article.api.dto.response.ArticleDetailResponse;
import com.codesight.article.api.dto.response.ArticleFeedPageResponse;
import com.codesight.article.api.dto.response.ArticlePatchResponse;
import com.codesight.article.service.ArticleFeedService;
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
    private final ArticleFeedService articleFeedService;

    /**
     * 创建文章或保存草稿
     *
     * @param request  创建请求参数
     * @param authorId 当前登录用户 ID
     * @return 创建响应（包含生成的分布式雪花 ID 与状态）
     */
    @PostMapping("/create")
    @Operation(summary = "创建文章或草稿", description = "自动解析 Markdown AST 智能提取摘要与字数，可直接发布或保存为草稿")
    @RateLimit()
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
    @RateLimit(maxRequests = 60)
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
    @RateLimit(maxRequests = 300)
    public ArticleDetailResponse getDetail(
            @PathVariable("id") Long id,
            @CurrentUserId(required = false) Long userId
            ) {
        return articleService.getDetail(id, userId);
    }

    /**
     * 获取文章信息流
     *
     * @param request 分页筛选参数
     * @param userId  当前登录用户 ID（可选）
     * @return 信息流分页数据
     */
    @GetMapping("/feed")
    @Operation(summary = "获取文章信息流", description = "支持最新与推荐排序、分类频道、标签聚合过滤以及游标分页")
    @RateLimit(maxRequests = 300)
    public ArticleFeedPageResponse getFeed(
            @Valid @ModelAttribute ArticleFeedRequest request,
            @CurrentUserId(required = false) Long userId) {
        return articleFeedService.getFeed(request, userId);
    }

    /**
     * 文章点赞/取消点赞
     *
     * @param id     目标文章 ID
     * @param isLike true 为点赞，false 为取消点赞
     * @param userId 当前登录用户 ID
     * @return 操作后状态是否翻转成功
     */
    @PostMapping("/{id}/like")
    @Operation(summary = "文章点赞/取消点赞", description = "基于位图原子翻转判重，状态真实改变时联动创作者获赞量")
    @RateLimit(maxRequests = 60)
    public boolean toggleLike(
            @PathVariable("id") Long id,
            @RequestParam("isLike") boolean isLike,
            @Parameter(hidden = true) @CurrentUserId Long userId) {
        return articleService.toggleLike(id, userId, isLike);
    }

    /**
     * 文章收藏/取消收藏
     *
     * @param id         目标文章 ID
     * @param isFavorite true 为收藏，false 为取消收藏
     * @param userId     当前登录用户 ID
     * @return 操作后状态是否翻转成功
     */
    @PostMapping("/{id}/favorite")
    @Operation(summary = "文章收藏/取消收藏", description = "基于位图原子翻转判重，防刷幂等")
    @RateLimit(maxRequests = 60)
    public boolean toggleFavorite(
            @PathVariable("id") Long id,
            @RequestParam("isFavorite") boolean isFavorite,
            @Parameter(hidden = true) @CurrentUserId Long userId) {
        return articleService.toggleFavorite(id, userId, isFavorite);
    }
}

