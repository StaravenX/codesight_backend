package com.codesight.auth.audit.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum LoginStatus {
    SUCCESS("SUCCESS", "登录成功"),
    FAILED("FAILED", "登录失败");

    @EnumValue
    private final String code;
    private final String description;
}
