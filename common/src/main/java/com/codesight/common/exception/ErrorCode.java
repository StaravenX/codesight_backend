package com.codesight.common.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    BAD_REQUEST("BAD_REQUEST", "业务参数错误"),
    VERIFICATION_RATE_LIMIT("VERIFICATION_RATE_LIMIT", "验证码发送过于频繁"),
    VERIFICATION_DAILY_LIMIT("VERIFICATION_DAILY_LIMIT", "验证码发送次数达到上限"),
    VERIFICATION_LOCKED("VERIFICATION_LOCKED", "验证尝试次数过多，账户已被锁定");

    final String code;
    final String msg;

    ErrorCode(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
