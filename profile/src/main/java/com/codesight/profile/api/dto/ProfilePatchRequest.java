package com.codesight.profile.api.dto;

import cn.hutool.core.util.StrUtil;
import com.codesight.user.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

        @Schema(description = "职业方向（如：移动端开发、前端开发、服务端、人工智能等）")
        @Size(max = 64, message = "职业方向长度不能超过 64")
        String jobDirection,

        @Schema(description = "职位（0-50位）")
        @Size(max = 50, message = "职位长度不能超过 50")
        String jobTitle,

        @Schema(description = "就职公司（0-50位）")
        @Size(max = 50, message = "公司名称长度不能超过 50")
        String company,

        @Schema(description = "开始工作时间（年月格式，如：2026-02）")
        @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "开始工作时间格式需为 YYYY-MM（如 2026-02）")
        String workDate,

        @Schema(description = "个人主页（0-100位）")
        @Size(max = 100, message = "个人主页长度不能超过 100")
        String homePage,

        @Schema(description = "个人介绍（0-100位）")
        @Size(max = 100, message = "个人介绍长度不能超过 100")
        String bio,

        @Schema(description = "感兴趣的技术领域（JSON 字符串数组）", example = "[\"移动开发\", \"软件设计与数据结构和算法\"]")
        String interestedDomains
) {
    /**
     * 检查是否至少提供了一个待更新的有效字段
     */
    public boolean hasAnyField() {
        return nickname != null
                || jobDirection != null
                || jobTitle != null
                || company != null
                || workDate != null
                || homePage != null
                || bio != null
                || interestedDomains != null;
    }

    /**
     * 转换为 User 局部更新实体对象
     *
     * @param userId 用户 ID
     * @return 用户更新实体
     */
    public User toEntity(Long userId) {
        return User.builder()
                .id(userId)
                .nickname(StrUtil.trim(nickname))
                .jobDirection(StrUtil.trim(jobDirection))
                .jobTitle(StrUtil.trim(jobTitle))
                .company(StrUtil.trim(company))
                .workDate(StrUtil.trim(workDate))
                .homePage(StrUtil.trim(homePage))
                .bio(StrUtil.trim(bio))
                .interestedDomains(StrUtil.trim(interestedDomains))
                .build();
    }
}