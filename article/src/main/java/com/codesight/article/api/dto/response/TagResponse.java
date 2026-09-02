package com.codesight.article.api.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 二级技术标签响应体
 */
@Schema(description = "二级技术标签响应体")
public record TagResponse(
        @Schema(description = "标签 ID")
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        @Schema(description = "标签名称")
        String name,

        @Schema(description = "该标签下文章聚合计数")
        Long articleCount
) {
    public TagResponse(Long id, String name) {
        this(id, name, null);
    }
}
