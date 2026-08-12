package com.codesight.auth.api.dto;

import com.codesight.auth.model.IdentifierType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 注册请求。
 * <p>
 * 字段：账号类型与值、验证码、可选密码、是否同意服务条款。
 * 验证：需通过验证码校验；当提供密码时需通过密码策略校验。
 */
@Schema(description = "注册请求")
public record RegisterRequest(
        @Schema(description = "账号类型", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "账号类型不能为空")
        IdentifierType identifierType,

        @Schema(description = "账号值", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "账号不能为空")
        String identifier,

        @Schema(description = "验证码", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "验证码不能为空")
        String code,

        @Schema(description = "密码 (可选)")
        String password,

        @Schema(description = "用户昵称 (可选)")
        String nickname,

        @Schema(description = "是否同意服务条款", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean agreeTerms
) {
}
