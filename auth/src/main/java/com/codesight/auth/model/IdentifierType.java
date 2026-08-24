package com.codesight.auth.model;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户登录类型
 * <p>
 * 判断用户是手机号登录还是邮箱登录
 */
@Getter
@AllArgsConstructor
public enum IdentifierType {
    PHONE("phone", "手机号"),
    EMAIL("email", "邮箱");

    @JsonValue
    private final String value;
    private final String description;
}
