package com.codesight.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.codesight.user.api.dto.UserProfileResponse;

/**
 * 认证响应。
 * <p>
 * 登录/注册成功后返回：包含用户信息与令牌信息的组合结果。
 */
@Schema(description = "认证响应")
public record AuthResponse(
        @Schema(description = "用户信息") UserProfileResponse user,
        @Schema(description = "令牌信息") TokenResponse token
) {
}
