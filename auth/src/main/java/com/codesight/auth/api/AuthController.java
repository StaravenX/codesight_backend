package com.codesight.auth.api;

import com.codesight.auth.api.dto.*;
import com.codesight.auth.model.ClientInfo;
import com.codesight.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
     * @param clientInfo 客户端信息（IP 与 User-Agent），记录审计日志。
     * @return 认证响应，包含用户信息与令牌对。
     */
    @PostMapping("/register")
    @Operation(summary = "注册新用户", description = "验证标识与验证码后创建用户，并签发token")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, ClientInfo clientInfo) {
        return authService.register(request, clientInfo);
    }

    /**
     * 密码登录并获取令牌对。
     * <p>
     * 密码登录；成功后签发 Access/Refresh Token。
     *
     * @param request     请求体，包含：标识值、密码
     * @param clientInfo 客户端信息（IP 与 User-Agent），记录审计日志。
     * @return 认证响应，包含用户信息与令牌对。
     */
    @PostMapping("/login/password")
    @Operation(summary = "密码登录", description = "使用手机号/邮箱和密码登录")
    public AuthResponse loginByPassword(@Valid @RequestBody LoginByPasswordRequest request, ClientInfo clientInfo) {
        return authService.loginByPassword(request, clientInfo);
    }

    /**
     * 验证码登录并获取令牌对。
     * <p>
     * 验证码登录；成功后签发 Access/Refresh Token。
     *
     * @param request     请求体，包含：标识值、验证码
     * @param clientInfo 客户端信息（IP 与 User-Agent），记录审计日志。
     * @return 认证响应，包含用户信息与令牌对。
     */
    @PostMapping("/login/code")
    @Operation(summary = "验证码登录", description = "使用手机号/邮箱和短信验证码登录")
    public AuthResponse loginByCode(@Valid @RequestBody LoginByCodeRequest request, ClientInfo clientInfo) {
        return authService.loginByCode(request, clientInfo);
    }

    /**
     * 登出并撤销刷新令牌。
     * <p>
     * 若提供的令牌为合法的 Refresh Token，则撤销其白名单记录；返回 204，无响应体。
     *
     * @param request 请求体，包含：refreshToken（欲撤销的刷新令牌）。
     * @return 空响应，HTTP 204 No Content。
     */
    @PostMapping("/logout")
    @Operation(summary = "登出", description = "登出并撤销刷新令牌")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * 使用 Refresh Token 刷新令牌。
     * <p>
     * 校验刷新令牌的合法性与白名单状态，签发新的令牌对，并撤销旧刷新令牌。
     *
     * @param request    请求体，包含：refreshToken（刷新令牌）。
     * @param clientInfo 客户端信息（IP 与 User-Agent），记录审计日志。
     * @return 新的令牌响应（accessToken/refreshToken 及其过期时间）。
     */
    @PostMapping("/token/refresh")
    @Operation(summary = "刷新令牌", description = "使用 Refresh Token 刷新令牌")
    public TokenResponse refresh(@Valid @RequestBody TokenRefreshRequest request, ClientInfo clientInfo) {
        return authService.refresh(request, clientInfo);
    }
}
