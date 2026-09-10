package com.codesight.search.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * 关键词全文检索请求参数
 */
@Builder
@Schema(description = "关键词全文检索请求参数")
public record SearchRequest(
        @Schema(description = "搜索关键词", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "搜索关键词不能为空")
        String q,

        @Schema(description = "单页拉取条数", defaultValue = "20")
        @Min(value = 1, message = "分页大小最小为 1")
        @Max(value = 50, message = "分页大小最大为 50")
        @NotNull(message = "分页大小不能为空")
        Integer size,

        @Schema(description = "上一页返回的游标")
        String after
) {
}
