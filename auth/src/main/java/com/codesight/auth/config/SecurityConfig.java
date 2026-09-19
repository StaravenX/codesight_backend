package com.codesight.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 安全配置。
 * <p>
 * - 关闭 CSRF；
 * - 启用 CORS，当前允许所有来源；
 * - 无状态会话；
 * - 公开认证相关接口与健康检查，其余接口需鉴权；
 * - 资源服务器启用 JWT 校验。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 配置 Spring Security 过滤链。
     * <p>
     * 主要包含：
     * </p>
     * - 关闭 CSRF；
     * - 启用 CORS；
     * - 使用无状态会话策略；
     * - 公开认证接口与健康检查，其余接口需鉴权；
     * - 启用资源服务器的 JWT 校验。
     * @param http Spring 的 {@link HttpSecurity} 构建器。
     * @param jwtDecoder 全局 JWT 解码器
     * @return 构建完成的 {@link SecurityFilterChain}。
     * @throws Exception 构建过滤链过程中可能抛出的异常。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session
                            .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 1. Knife4j 和 Swagger 的静态资源与接口文档
                .requestMatchers(
                    "/doc.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/webjars/**",
                    "/swagger-resources/**"
                ).permitAll()

                // 2. 认证公开接口（登录、注册、发验证码、刷新Token、重置密码等）
                .requestMatchers("/api/v1/auth/**").permitAll()

                // 3. 全站技术分类与标签树公开导航
                .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()

                // 4. 文章公开浏览接口（文章详情、首页/频道 Feed 流、详情页底部分割相关推荐）
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/articles/detail/**",
                    "/api/v1/articles/feed",
                    "/api/v1/articles/*/related",
                    "/api/v1/search/articles/*/related"
                ).permitAll()

                // 5. 文章全局全文检索
                .requestMatchers(HttpMethod.GET, "/api/v1/search").permitAll()

                // 6. 创作者公开名片
                .requestMatchers(HttpMethod.GET, "/api/v1/profile/authors/**").permitAll()

                // 7. 用户公开关注/粉丝列表
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/relations/following",
                    "/api/v1/relations/followers"
                ).permitAll()

                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                    jwt.decoder(token -> {
                        Jwt decodedJwt = jwtDecoder.decode(token);
                        if (!"access".equals(decodedJwt.getClaimAsString("token_type"))) {
                            throw new JwtException("无效的令牌类型，必须是 access token");
                        }
                        return decodedJwt;
                    })
            ));
        return http.build();
    }

    /**
     * 定义并提供 CORS 配置源。
     * <p>
     * 当前允许所有来源，允许常见方法与请求头，且不携带凭证。
     * </p>
     * @return {@link CorsConfigurationSource}，用于为所有路径注册 CORS 规则。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
