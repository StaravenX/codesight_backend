package com.codesight.profile.api.dto;

import cn.hutool.core.util.StrUtil;
import com.codesight.user.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 个人资料局部更新请求（PATCH）
 * <p>
 * 客户端仅需提交欲更新的字段；未提交或为 null 的字段保持不变。
 * 注意：平台唯一标识 csId 为后端系统保留字段，不开放客户端自行修改。
 */
@Schema(description = "个人资料局部更新请求体")
public record ProfilePatchRequest(
        @Schema(description = "用户昵称（1-64位）")
        @Size(min = 1, max = 64, message = "昵称长度需在 1-64 之间")
        String nickname,

        @Schema(description = "个人简介（不超过512字）")
        @Size(max = 512, message = "个人描述长度不能超过 512")
        String bio,

        @Schema(description = "性别（MALE/FEMALE/OTHER/UNKNOWN）")
        @Pattern(regexp = "(?i)MALE|FEMALE|OTHER|UNKNOWN", message = "性别取值为 MALE/FEMALE/OTHER/UNKNOWN")
        String gender,

        @Schema(description = "出生日期")
        @PastOrPresent(message = "生日不能晚于今天")
        LocalDate birthday,

        @Schema(description = "就职公司")
        @Size(max = 128, message = "公司名称长度不能超过 128")
        String company,

        @Schema(description = "职位/头衔")
        @Size(max = 64, message = "职位长度不能超过 64")
        String jobTitle,

        @Schema(description = "院校/学校名称")
        @Size(max = 128, message = "学校名称长度不能超过 128")
        String school,

        @Schema(description = "感兴趣的技术领域（JSON 字符串数组）", example = "[\"Java\", \"Spring Cloud\", \"分布式\"]")
        String interestedDomains
) {
    /**
     * 检查是否至少提供了一个待更新的有效字段
     */
    public boolean hasAnyField() {
        return nickname != null
                || bio != null
                || gender != null
                || birthday != null
                || company != null
                || jobTitle != null
                || school != null
                || interestedDomains != null;
    }

    /**
     * 转换为 User 局部更新实体对象
     * <p>
     *
     * @param userId 用户 ID
     * @return 用户更新实体
     */
    public User toEntity(Long userId) {
        return User.builder()
                .id(userId)
                .nickname(StrUtil.trim(nickname))
                .bio(StrUtil.trim(bio))
                .gender(StrUtil.isNotBlank(gender) ? gender.trim().toUpperCase() : null)
                .birthday(birthday)
                .company(StrUtil.trim(company))
                .jobTitle(StrUtil.trim(jobTitle))
                .school(StrUtil.trim(school))
                .interestedDomains(StrUtil.trim(interestedDomains))
                .build();
    }
}