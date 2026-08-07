package com.codesight.auth.api;

import com.codesight.auth.api.dto.AuthResponse;
import com.codesight.auth.api.dto.RegisterRequest;
import com.codesight.auth.api.dto.SendCodeRequest;
import com.codesight.auth.api.dto.SendCodeResponse;
import com.codesight.auth.model.ClientInfo;
import com.codesight.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/auth")
@Validated
@Tag(name = "认证模块", description = "提供用户登录、注册、验证码等核心认证接口")
public class AuthController {

    private final AuthService authService;

    /**
     * 发送短信/邮箱验证码。
     * <p>
     * 根据场景（注册、登录、重置密码）向指定标识（手机号或邮箱）发送一次性验证码。
     *
     * @param request 请求体，包含：
     *                - identifierType：标识类型，PHONE 或 EMAIL；
     *                - identifier：手机号或邮箱地址；
     *                - scene：验证码使用场景（REGISTER/LOGIN/RESET_PASSWORD）。
     * @return 响应体，包含目标标识、场景以及验证码过期秒数。
     */
    @PostMapping("/send-code")
    @Operation(summary = "发送验证码", description = "根据场景向手机号或邮箱发送一次性验证码")
    public SendCodeResponse sendCode(@Valid @RequestBody SendCodeRequest request) {
        return authService.sendCode(request);
    }

    /**
     * 注册新用户并自动登录。
     * <p>
     * 验证标识与验证码后创建用户，若提供密码则进行复杂度校验并保存密码哈希；成功后签发 Access/Refresh Token。
     *
     * @param request     请求体，包含：标识类型与值、验证码、可选密码、是否同意协议。
     * @param httpRequest 用于解析客户端信息（IP 与 User-Agent），记录审计日志。
     * @return 认证响应，包含用户信息与令牌对。
     */
    @PostMapping("/register")
    @Operation(summary = "注册新用户", description = "验证标识与验证码后创建用户，并签发token")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        return authService.register(request, resolveClient(httpRequest));
    }

    /**
     * 从请求中解析客户端信息（IP 与 User-Agent）。
     *
     * @param request HTTP 请求对象。
     * @return 客户端信息。
     */
    private ClientInfo resolveClient(HttpServletRequest request) {
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
