package com.codesight.auth.verification.model;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 验证码使用场景
 */
@Getter
@AllArgsConstructor
public enum VerificationScene {
    REGISTER("register", "用户注册"),
    LOGIN("login", "用户登录"),
    RESET_PASSWORD("reset_password", "重置密码");

    @JsonValue
    private final String value;
    private final String description;
}
