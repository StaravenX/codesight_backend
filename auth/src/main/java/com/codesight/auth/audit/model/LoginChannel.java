package com.codesight.auth.audit.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "登录渠道")
public enum LoginChannel {
    REGISTER("REGISTER", "账号注册"),
    PASSWORD("PASSWORD", "密码登录"),
    CODE("CODE", "验证码登录"),
    TOKEN_REFRESH("TOKEN_REFRESH", "令牌刷新"),
    PASSWORD_RESET("PASSWORD_RESET", "密码重置");

    @EnumValue
    private final String code;
    private final String description;

    LoginChannel(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
