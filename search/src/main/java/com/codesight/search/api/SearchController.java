package com.codesight.search.api;

import com.codesight.common.annotation.CurrentUserId;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全文搜索控制器：
 * - 关键词检索（多字段召回、互动加权、高亮摘要、游标深度分页）
 */
@RestController
@RequestMapping("/api/v1/search")
@Validated
@RequiredArgsConstructor
@Tag(name = "搜索接口", description = "基于 Elasticsearch 的全文检索")
public class SearchController {

    private final SearchService searchService;

    /**
     * 关键词检索
     */
    @GetMapping
    @Operation(summary = "关键词全文检索", description = "支持相关性 + 点赞阅读平滑加权，支持标签筛选、游标深度分页与关键词高亮")
    public SearchResponse search(
            @Valid @ModelAttribute SearchRequest request,
            @Parameter(hidden = true) @CurrentUserId(required = false) Long currentUserId) {
        return searchService.search(request, currentUserId);
    }
}

