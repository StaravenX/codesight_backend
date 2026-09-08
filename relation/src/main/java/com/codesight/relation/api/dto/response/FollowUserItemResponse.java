package com.codesight.relation.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 关注/粉丝人脉列表用户卡片响应体
 */
@Builder
@Schema(description = "关注/粉丝人脉列表用户卡片")
public record FollowUserItemResponse(
        @Schema(description = "用户 ID")
        Long userId,

        @Schema(description = "用户昵称")
        String nickname,

        @Schema(description = "用户头像 URL")
        String avatar,

        @Schema(description = "粉丝总数")
        Long followerCount,

        @Schema(description = "关注总数")
        Long followingCount,

        @Schema(description = "当前登录用户是否关注了此用户（未登录时为 false）")
        Boolean followedByMe
) {
}
