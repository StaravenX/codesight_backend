package com.codesight.profile.api.dto;

import com.codesight.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 个人资料响应 DTO
 */
@Schema(description = "个人资料响应体")
public record ProfileResponse(
        @Schema(description = "用户ID")
        Long id,

        @Schema(description = "用户昵称")
        String nickname,

        @Schema(description = "头像URL")
        String avatar,

        @Schema(description = "个人简介")
        String bio,

        @Schema(description = "平台唯一ID")
        String csId,

        @Schema(description = "性别")
        String gender,

        @Schema(description = "出生日期")
        LocalDate birthday,

        @Schema(description = "就职公司")
        String company,

        @Schema(description = "职位")
        String jobTitle,

        @Schema(description = "院校/学校")
        String school,

        @Schema(description = "手机号码")
        String phone,

        @Schema(description = "邮箱地址")
        String email,

        @Schema(description = "感兴趣的技术领域")
        String interestedDomains,

        @Schema(description = "账号创建时间")
        Instant createdTime
) {
    /**
     * 从 User 实体转换为对外响应对象
     *
     * @param user 用户实体
     * @return 个人资料响应 DTO
     */
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
                user.getGender(),
                user.getBirthday(),
                user.getCompany(),
                user.getJobTitle(),
                user.getSchool(),
                user.getPhone(),
                user.getEmail(),
                user.getInterestedDomains(),
                user.getCreatedTime()
        );
    }
}