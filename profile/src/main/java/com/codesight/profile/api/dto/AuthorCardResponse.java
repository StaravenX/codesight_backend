package com.codesight.profile.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创作者名片响应体
 */
@Schema(description = "创作者名片卡片响应体")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AuthorCardResponse {
        @Schema(description = "作者用户ID")
        Long id;

        @Schema(description = "昵称")
        String nickname;

        @Schema(description = "头像")
        String avatar;

        @Schema(description = "个人简介")
        String bio;

        @Schema(description = "职位")
        String jobTitle;

        @Schema(description = "就职公司")
        String company;

        @Schema(description = "总获阅读量")
        long viewsReceived;

        @Schema(description = "总获点赞量")
        long likesReceived;

        @Schema(description = "粉丝总数")
        long followerCount;

        @Schema(description = "关注总数")
        long followingCount;

        @Schema(description = "当前登录用户是否已关注该作者")
        boolean isFollowed;
}
