package com.codesight.search.api;

import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import com.codesight.common.annotation.CurrentUserId;
import com.codesight.common.annotation.RateLimit;
import com.codesight.search.api.dto.request.SearchRequest;
import com.codesight.search.api.dto.response.SearchResponse;
import com.codesight.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 搜索与推荐控制器：
 * - 关键词全文检索
 * - 文章详情页相关推荐（基于向量相似度）
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
@Tag(name = "搜索与推荐接口", description = "基于 Elasticsearch 的全文检索与向量相似度相关推荐")
public class SearchController {

    private final SearchService searchService;

    /**
     * 关键词检索
     */
    @GetMapping("/search")
    @RateLimit(maxRequests = 120)
    @Operation(summary = "关键词全文检索", description = "支持相关性 + 点赞阅读平滑加权，支持标签筛选、游标深度分页与关键词高亮")
    public SearchResponse search(
            @Valid @ModelAttribute SearchRequest request,
            @Parameter(hidden = true) @CurrentUserId(required = false) Long currentUserId) {
        return searchService.search(request, currentUserId);
    }

    /**
     * 文章相关推荐（Top-5）
     */
    @GetMapping({"/articles/{id}/related", "/search/articles/{id}/related"})
    @RateLimit(maxRequests = 300)
    @Operation(summary = "获取文章相关推荐", description = "基于稠密向量余弦相似度召回相关文章，并组装实时计数与点赞态")
    public List<ArticleFeedItemResponse> getRelated(
            @PathVariable("id") Long id,
            @Parameter(hidden = true) @CurrentUserId(required = false) Long userId) {
        return searchService.listRelatedArticles(id, userId);
    }
}

