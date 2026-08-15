package com.codesight.common.web;

import com.codesight.common.annotation.CurrentUserId;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 当前登录用户 ID 参数解析器。
 * <p>
 * 拦截标注了 {@link CurrentUserId} 的 {@code Long} / {@code long} 类型参数，
 * 自动从 Spring Security 上下文中的 {@link Jwt} 提取用户主体标识（Subject）并转换为 Long 注入。
 */
public class UserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class) &&
                (Long.class.equals(parameter.getParameterType()) || long.class.equals(parameter.getParameterType()));
    }

    @Nullable
    @Override
    public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                try {
                    return Long.parseLong(subject);
                } catch (NumberFormatException ex) {
                    throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户标识格式非法");
                }
            }
        }

        CurrentUserId annotation = parameter.getParameterAnnotation(CurrentUserId.class);
        if (annotation != null && annotation.required()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户未登录或会话已失效");
        }

        return null;
    }
}