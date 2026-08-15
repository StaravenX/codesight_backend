package com.codesight.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自动从安全上下文中提取当前登录用户的 ID。
 * <p>
 * 可直接标记在 Controller 方法的 {@code Long userId} 或 {@code long userId} 参数上。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {

    /**
     * 是否必须登录。
     * <p>
     * 默认为 {@code true}。当为 {@code true} 且当前未认证时，将抛出 {@code UNAUTHORIZED} 异常；
     * 当为 {@code false} 时，若未认证则参数被注入为 {@code null}。
     */
    boolean required() default true;
}
