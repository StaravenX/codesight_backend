package com.codesight.auth.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求。
 * <p>
 * 传入旧的刷新令牌，服务器验证后返回新的访问/刷新令牌对。
 */
@Schema(description = "刷新令牌请求")
public record TokenRefreshRequest(
        @Schema(description = "旧的刷新令牌", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "刷新令牌不能为空")
        String refreshToken) {
}
