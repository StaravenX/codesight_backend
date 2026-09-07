package com.codesight.relation.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 双方社交关系响应体
 */
@Schema(description = "双方社交关系响应体")
public record RelationStatusResponse(
        @Schema(description = "当前用户是否关注了目标用户")
        Boolean following,

        @Schema(description = "目标用户是否关注了当前用户（是否被关注）")
        Boolean followedBy
) {
}
