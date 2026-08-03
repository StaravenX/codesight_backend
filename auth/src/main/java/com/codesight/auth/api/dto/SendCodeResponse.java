package com.codesight.auth.api.dto;

import com.codesight.auth.verification.model.VerificationScene;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发送验证码响应。
 *
 * @param identifier    规范化后的账号标识（手机号或邮箱）
 * @param scene         验证码场景
 * @param expireSeconds 验证码有效期（秒）
 */
@Schema(description = "发送验证码响应对象")
public record SendCodeResponse(
        @Schema(description = "规范化后的账号标识", example = "13800000000")
        @NotBlank 
        String identifier,
        
        @Schema(description = "验证码场景", example = "LOGIN")
        @NotNull 
        VerificationScene scene,
        
        @Schema(description = "验证码有效期（秒）", example = "300")
        @NotNull 
        int expireSeconds
) {
}
