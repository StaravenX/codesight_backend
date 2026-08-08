package com.codesight.auth.audit.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "登录状态")
public enum LoginStatus {
    SUCCESS("SUCCESS", "登录成功"),
    FAILED("FAILED", "登录失败");

    @EnumValue
    private final String code;
    private final String description;

    LoginStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
