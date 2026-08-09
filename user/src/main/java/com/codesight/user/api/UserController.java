package com.codesight.user.api;

import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.user.User;
import com.codesight.user.UserService;
import com.codesight.user.api.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/users")
@Tag(name = "用户模块", description = "提供用户资料管理等核心业务接口")
public class UserController {

    private final UserService userService;

    /**
     * 获取当前登录用户信息
     *
     * @param jwt 当前请求绑定的 JWT 令牌
     * @return 用户个人资料响应
     */
    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息", description = "基于 JWT 解析并返回当前登录用户的概要信息")
    public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或已被删除");
        }
        
        return UserProfileResponse.from(user);
    }
}
