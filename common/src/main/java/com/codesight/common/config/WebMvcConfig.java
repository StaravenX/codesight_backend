package com.codesight.common.config;

import com.codesight.common.web.ClientInfoArgumentResolver;
import com.codesight.common.web.RateLimitInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.List;
/**
 * 核心 Web 配置类（Common 模块）。
 * <p>
 * 为所有依赖此模块的微服务自动注册基础组件，例如：
 * - 客户端信息解析器 (ClientInfo)
 * - 全局 IP 限流拦截器 (RateLimit)
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final StringRedisTemplate stringRedisTemplate;

    public WebMvcConfig(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ClientInfoArgumentResolver());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(stringRedisTemplate))
                .addPathPatterns("/**");
    }
}