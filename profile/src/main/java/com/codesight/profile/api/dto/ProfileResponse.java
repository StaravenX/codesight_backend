package com.codesight.profile.api.dto;

import com.codesight.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 用户个人资料统一响应体（对标掘金个人主页与基本信息）
 */
@Schema(description = "个人资料响应体")
public record ProfileResponse(
        @Schema(description = "用户ID")
        Long id,

        @Schema(description = "用户名")
        String nickname,

        @Schema(description = "头像URL")
        String avatar,

        @Schema(description = "个人介绍")
        String bio,

        @Schema(description = "平台唯一极客号")
        String csId,

        @Schema(description = "职业方向")
        String jobDirection,

        @Schema(description = "职位")
        String jobTitle,

        @Schema(description = "就职公司")
        String company,

        @Schema(description = "开始工作时间（年月格式，如：2026-02）")
        String workDate,

        @Schema(description = "个人主页")
        String homePage,

        @Schema(description = "手机号码")
        String phone,

        @Schema(description = "邮箱地址")
        String email,

        @Schema(description = "兴趣标签")
        String interestedDomains,

        @Schema(description = "账号创建时间")
        Instant createdTime
) {
    public static ProfileResponse from(User user) {
        if (user == null) {
            return null;
        }
        return new ProfileResponse(
                user.getId(),
                user.getNickname(),
                user.getAvatar(),
                user.getBio(),
                user.getCsId(),
                user.getJobDirection(),
                user.getJobTitle(),
                user.getCompany(),
                user.getWorkDate(),
                user.getHomePage(),
                user.getPhone(),
                user.getEmail(),
                user.getInterestedDomains(),
                user.getCreatedTime()
        );
    }
}