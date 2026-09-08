package com.codesight.relation.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * 关注/粉丝人脉列表游标分页查询请求参数
 */
@Builder
@Schema(description = "关注/粉丝人脉列表游标分页查询参数")
public record FollowListQueryRequest(
        @Schema(description = "目标用户 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "目标用户ID不能为空")
        Long userId,

        @Schema(description = "单页条数，默认 20，最大 50", defaultValue = "20")
        @Min(value = 1, message = "单页条数最小为 1")
        @Max(value = 50, message = "单页条数最大为 50")
        Integer limit,

        @Schema(description = "分页游标")
        String cursor
) {
}
