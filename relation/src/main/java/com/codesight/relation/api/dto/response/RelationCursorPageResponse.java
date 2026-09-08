package com.codesight.relation.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

/**
 * 游标分页响应包装体
 *
 * @param <T> 数据项泛型
 */
@Builder
@Schema(description = "游标分页通用响应包装体")
public record RelationCursorPageResponse<T>(
        @Schema(description = "当前页数据项列表")
        List<T> items,

        @Schema(description = "下一页查询游标")
        String nextCursor,

        @Schema(description = "是否还有更多数据")
        Boolean hasMore
) {
}
