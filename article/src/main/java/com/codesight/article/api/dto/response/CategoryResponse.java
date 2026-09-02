package com.codesight.article.api.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一级技术分类响应体
 */
@Schema(description = "一级技术分类响应体")
public record CategoryResponse(
        @Schema(description = "分类 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        @Schema(description = "分类名称")
        String name,

        @Schema(description = "英文路由标识")
        String slug,

        @Schema(description = "排序权重（升序）")
        Integer sortOrder,

        @Schema(description = "分类图标 URL")
        String iconUrl
) {
}
