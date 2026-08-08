package com.codesight.common.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    BAD_REQUEST("BAD_REQUEST", "业务参数错误"),
    VERIFICATION_RATE_LIMIT("VERIFICATION_RATE_LIMIT", "验证码发送过于频繁"),
    VERIFICATION_DAILY_LIMIT("VERIFICATION_DAILY_LIMIT", "验证码发送次数达到上限"),
    VERIFICATION_LOCKED("VERIFICATION_LOCKED", "验证尝试次数过多，账户已被锁定"),
    VERIFICATION_NOT_FOUND("VERIFICATION_NOT_FOUND", "未找到相应验证码，或已过期"),
    VERIFICATION_MISMATCH("VERIFICATION_MISMATCH", "验证码错误"),
    VERIFICATION_TOO_MANY_ATTEMPTS("VERIFICATION_TOO_MANY_ATTEMPTS", "失败的尝试次过多"),
    TERMS_NOT_ACCEPTED("TERMS_NOT_ACCEPTED", "未同意服务条款"),
    IDENTIFIER_EXISTS("IDENTIFIER_EXISTS", "标识已存在"),
    PASSWORD_POLICY_VIOLATION("PASSWORD_POLICY_VIOLATION", "密码不符合规则"),
    IDENTIFIER_NOT_FOUND("IDENTIFIER_NOT_FOUND", "标识不存在"),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "无效的凭证");

    final String code;
    final String msg;

    ErrorCode(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
