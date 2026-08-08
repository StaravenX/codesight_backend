package com.codesight.auth.api.dto;

import com.codesight.auth.model.IdentifierType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 密码登录请求
 * @param type 标识类型
 * @param identifier 账号值
 * @param password 密码
 */
@Schema(description = "密码登录请求")
public record LoginByPasswordRequest(
        @Schema(description = "标识类型", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "标识类型不能为空")
        IdentifierType type,
        @Schema(description = "账号值", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "账号不能为空")
        String identifier,
        @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "密码不能为空")
        String password
) {
}
