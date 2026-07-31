package com.codesight.common.web;

import com.codesight.common.exception.ErrorCode;

/**
 * 统一的 API 返回包装体。
 * <p>
 * 用于封装 HTTP 响应中的业务数据和状态，
 * <p>
 *
 * @param code    业务状态码。
 *                成功固定为 "SUCCESS"，失败为具体业务错误码。
 * @param message 业务提示信息。
 *                成功为 "操作成功"，失败为具体的错误提示，可直接展示给用户。
 * @param data    响应的业务数据。
 *                发生错误或无数据时通常为 null。
 */
public record Result<T>(String code, String message, T data) {

    /**
     * 构造成功的响应（无数据）。
     */
    public static <T> Result<T> success() {
        return new Result<>("SUCCESS", "操作成功", null);
    }

    /**
     * 构造成功的响应（含数据）。
     *
     * @param data 业务数据
     */
    public static <T> Result<T> success(T data) {
        return new Result<>("SUCCESS", "操作成功", data);
    }

    /**
     * 构造失败的响应。
     *
     * @param code    错误码字符串
     * @param message 错误信息
     */
    public static <T> Result<T> error(String code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 根据 ErrorCode 枚举构造失败的响应。
     *
     * @param errorCode 系统定义的错误码枚举
     */
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMsg(), null);
    }
}
