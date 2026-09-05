package com.codesight.article.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 信息流无限滚动分页响应体
 */
@Schema(description = "信息流无限滚动分页响应体")
public record ArticleFeedPageResponse(
        @Schema(description = "文章卡片列表")
        List<ArticleFeedItemResponse> items,

        @Schema(description = "下一页查询游标（为 null 或空时表示已到底部）")
        String nextCursor,

        @Schema(description = "是否还有更多数据")
        Boolean hasMore
) {
}
