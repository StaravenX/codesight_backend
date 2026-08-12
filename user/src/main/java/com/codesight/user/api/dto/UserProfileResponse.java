package com.codesight.user.api.dto;

import com.codesight.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

@Schema(description = "用户个人资料响应")
public record UserProfileResponse(
        @Schema(description = "用户ID") long id,
        @Schema(description = "极客号") String csId,
        @Schema(description = "昵称") String nickname,
        @Schema(description = "头像") String avatar,
        @Schema(description = "手机号") String phone,
        @Schema(description = "邮箱") String email,
        @Schema(description = "个人简介") String bio,
        @Schema(description = "性别") String gender,
        @Schema(description = "出生日期") LocalDate birthday,
        @Schema(description = "就职公司") String company,
        @Schema(description = "职位") String jobTitle,
        @Schema(description = "院校") String school,
        @Schema(description = "感兴趣的技术领域") String interestedDomains,
        @Schema(description = "账号创建时间") Instant createdTime
) {
    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
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
                user.getInterestedDomains(),
                user.getCreatedTime()
        );
    }
}
