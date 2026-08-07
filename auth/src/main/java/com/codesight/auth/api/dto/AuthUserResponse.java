package com.codesight.auth.api.dto;

import java.time.LocalDate;

import com.codesight.user.User;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 认证用户响应。
 * <p>
 * 面向客户端展示的基础用户信息，供“我是谁”与首页显示使用。
 */
@Schema(description = "认证用户响应")
public record AuthUserResponse(
        @Schema(description = "用户 ID") Long id,
        @Schema(description = "平台唯一ID (csId)") String csId,
        @Schema(description = "昵称") String nickname,
        @Schema(description = "头像") String avatar,
        @Schema(description = "手机号") String phone,
        @Schema(description = "邮箱地址") String email,
        @Schema(description = "个人简介") String bio,
        @Schema(description = "性别") String gender,
        @Schema(description = "出生日期") LocalDate birthday,
        @Schema(description = "就职公司") String company,
        @Schema(description = "职位") String jobTitle,
        @Schema(description = "院校") String school,
        @Schema(description = "感兴趣的技术领域") String interestedDomains
) {
    public AuthUserResponse(User user) {
        this(
                user.getId(),
                user.getCsId(),
                user.getNickname(),
                user.getAvatar(),
                user.getPhone(),
                user.getEmail(),
                user.getBio(),
                user.getGender(),
                user.getBirthday(),
                user.getCompany(),
                user.getJobTitle(),
                user.getSchool(),
                user.getInterestedDomains()
        );
    }
}
