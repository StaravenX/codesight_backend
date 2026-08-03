package com.codesight.auth.api.dto;

import com.codesight.auth.model.IdentifierType;
import com.codesight.auth.verification.model.VerificationScene;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 发送验证码请求
 * <p>
 * 配合账号类型与值用于生成并发送验证码
 * @param scene 验证码场景（如：LOGIN, REGISTER）
 * @param type 账号类型（如：PHONE, EMAIL）
 * @param identifier 账号标识（手机号或邮箱地址）
 */
@Schema(description = "发送验证码请求对象")
public record SendCodeRequest(
        @Schema(description = "验证码场景（如：LOGIN, REGISTER）", example = "LOGIN", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "场景不能为空") 
        VerificationScene scene,
        
        @Schema(description = "账号类型（如：PHONE, EMAIL）", example = "PHONE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "账号类型不能为空") 
        IdentifierType type,
        
        @Schema(description = "账号标识（手机号或邮箱地址）", example = "13800000000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "账号不能为空") 
        String identifier
) {
}
