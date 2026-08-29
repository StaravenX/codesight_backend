package com.codesight.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    BAD_REQUEST("BAD_REQUEST", "业务参数错误", 400),
    VERIFICATION_RATE_LIMIT("VERIFICATION_RATE_LIMIT", "验证码发送过于频繁", 429),
    VERIFICATION_DAILY_LIMIT("VERIFICATION_DAILY_LIMIT", "验证码发送次数达到上限", 429),
    VERIFICATION_LOCKED("VERIFICATION_LOCKED", "验证尝试次数过多，账户已被锁定", 423),
    VERIFICATION_NOT_FOUND("VERIFICATION_NOT_FOUND", "未找到相应验证码，或已过期", 404),
    VERIFICATION_MISMATCH("VERIFICATION_MISMATCH", "验证码错误", 400),
    VERIFICATION_TOO_MANY_ATTEMPTS("VERIFICATION_TOO_MANY_ATTEMPTS", "失败的尝试次过多", 429),
    TERMS_NOT_ACCEPTED("TERMS_NOT_ACCEPTED", "未同意服务条款", 400),
    IDENTIFIER_EXISTS("IDENTIFIER_EXISTS", "标识已存在", 409),
    PASSWORD_POLICY_VIOLATION("PASSWORD_POLICY_VIOLATION", "密码不符合规则", 400),
    IDENTIFIER_NOT_FOUND("IDENTIFIER_NOT_FOUND", "标识不存在", 404),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "无效的凭证", 401),
    LOGIN_LOCKED("LOGIN_LOCKED", "登录失败次数过多，账号已被临时锁定", 423),
    TOO_MANY_REQUESTS("TOO_MANY_REQUESTS", "请求过于频繁，请稍后再试", 429),
    UNAUTHORIZED("UNAUTHORIZED", "登录已失效，请重新登录", 401),
    CATEGORY_NOT_FOUND("CATEGORY_NOT_FOUND", "所选技术分类不存在", 404),
    ARTICLE_NOT_FOUND("ARTICLE_NOT_FOUND", "文章不存在或已被删除", 404),
    ARTICLE_FORBIDDEN("ARTICLE_FORBIDDEN", "无权修改他人文章", 403),
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", "服务器内部错误", 500);

    final String code;
    final String msg;
    final int httpStatus;

}
