package com.codesight.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解，应用于方法上
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {
    /**
     * 允许的最大请求次数
     */
    int maxRequests() default 10;

    /**
     * 时间窗口（秒）
     */
    int windowSeconds() default 60;
}
