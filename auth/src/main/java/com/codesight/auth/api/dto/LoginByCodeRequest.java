package com.codesight.auth.api.dto;

import com.codesight.auth.model.IdentifierType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 验证码登录请求
 * @param type 标识类型
 * @param identifier 账号值
 * @param code 验证码
 */
@Schema(description = "验证码登录请求")
public record LoginByCodeRequest(
        @Schema(description = "标识类型", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "标识类型不能为空")
        IdentifierType type,
        @Schema(description = "账号值", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "账号不能为空")
        String identifier,
        @Schema(description = "验证码", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "验证码不能为空")
        String code
) {
}

