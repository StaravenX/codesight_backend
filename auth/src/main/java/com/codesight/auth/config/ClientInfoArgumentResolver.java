package com.codesight.auth.config;

import com.codesight.auth.model.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * ClientInfo 参数解析器。
 * <p>
 * 拦截 Controller 中声明为 ClientInfo 类型的参数，自动从 HTTP Header 中提取 IP 和 User-Agent，
 * 
 */
public class ClientInfoArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(ClientInfo.class);
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            return new ClientInfo("unknown", "unknown");
        }

        String ua = request.getHeader("User-Agent");

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return new ClientInfo(forwarded.split(",")[0].trim(), ua);
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return new ClientInfo(realIp.trim(), ua);
        }

        return new ClientInfo(request.getRemoteAddr(), ua);
    }
}
