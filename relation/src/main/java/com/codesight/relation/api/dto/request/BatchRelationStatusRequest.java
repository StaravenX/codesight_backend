package com.codesight.relation.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量关注状态查询请求参数
 */
@Schema(description = "批量关注状态查询请求参数")
public record BatchRelationStatusRequest(
        @Schema(description = "目标用户 ID 集合（单次上限 100）")
        @NotEmpty(message = "目标用户列表不能为空")
        @Size(max = 100, message = "单次最多批量查询 100 个用户")
        List<Long> targetUserIds
) {
}
