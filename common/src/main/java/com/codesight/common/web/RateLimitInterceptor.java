package com.codesight.common.web;

import com.codesight.common.annotation.RateLimit;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collections;

public class RateLimitInterceptor implements HandlerInterceptor {
    private final DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.redisScript.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        this.redisScript.setResultType(Long.class);
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if(!(handler instanceof HandlerMethod method)) return true;

        RateLimit rateLimit = method.getMethodAnnotation(RateLimit.class);
        if(rateLimit == null) return true;

        int windowSeconds = rateLimit.windowSeconds();
        int maxRequests = rateLimit.maxRequests();
        String uri = request.getRequestURI();
        String key = "rate_limit:ip:" + getIp(request) + ":" + uri;
        
        Long result = stringRedisTemplate.execute(
                redisScript, 
                Collections.singletonList(key), 
                String.valueOf(maxRequests), 
                String.valueOf(windowSeconds)
        );
        
        if(result == 0L) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return true;
    }

    private String getIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }
}
