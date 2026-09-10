package com.codesight.search.api.dto.response;

import com.codesight.article.api.dto.response.ArticleFeedItemResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "全文搜索分页响应体")
public record SearchResponse(
        @Schema(description = "搜索命中的文章列表")
        List<ArticleFeedItemResponse> items,

        @Schema(description = "下一页游标")
        String after,

        @Schema(description = "是否还有更多数据")
        boolean hasMore
) {}
