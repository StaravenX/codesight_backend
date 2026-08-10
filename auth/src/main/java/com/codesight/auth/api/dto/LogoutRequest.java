package com.codesight.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 登出请求。
 * <p>
 * 传入刷新令牌以撤销对应会话，确保令牌不可再用。
 */
@Schema(description = "登出请求")
public record LogoutRequest(
        @Schema(description = "刷新令牌", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "刷新令牌不能为空")
        String refreshToken) {
}