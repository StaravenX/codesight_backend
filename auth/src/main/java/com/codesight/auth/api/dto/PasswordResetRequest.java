package com.codesight.auth.api.dto;

import com.codesight.auth.model.IdentifierType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 重置密码请求。
 * <p>
 * 字段：账号类型与值、验证码、新密码
 */
@Schema(description = "重置密码请求")
public record PasswordResetRequest(

    @Schema(description = "账号类型", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "账号类型不能为空")
    IdentifierType identifierType,

    @Schema(description = "账号值", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "账号不能为空")
    String identifier,

    @Schema(description = "验证码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "验证码不能为空")
    String code,

    @Schema(description = "新密码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "新密码不能为空")
    String newPassword

) {
}
