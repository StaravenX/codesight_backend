package com.codesight.profile.api;

import com.codesight.profile.api.dto.ProfilePatchRequest;
import com.codesight.profile.api.dto.ProfileResponse;
import com.codesight.profile.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人资料接口控制器
 * <p>
 * 提供当前登录用户的个人资料局部更新、头像直传写入等接口。
 * 控制器直接返回业务响应对象，由 GlobalResponseAdvice 统一包装为 Result。
 */
@Tag(name = "个人资料接口", description = "提供当前登录用户的资料修改与头像更新接口")
@RestController
@RequestMapping("/api/v1/profile")
@Validated
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /**
     * 更新个人资料（支持部分字段 PATCH 局部更新）
     *
     * @param jwt     当前请求绑定的 JWT 凭据
     * @param request 待更新字段请求体
     * @return 更新后的个人资料快照（由全局拦截器自动包装为 Result）
     */
    @Operation(summary = "修改个人资料", description = "支持对用户资料进行局部更新（PATCH），未传入的字段保持不变")
    @PatchMapping
    public ProfileResponse patch(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ProfilePatchRequest request
    ) {
        return profileService.updateProfile(jwt, request);
    }

    /**
     * 上传头像并更新用户头像地址
     *
     * @param jwt  当前请求绑定的 JWT 凭据
     * @param file 头像文件（multipart/form-data）
     * @return 更新后的个人资料快照（由全局拦截器自动包装为 Result）
     */
    @Operation(summary = "上传用户头像", description = "上传头像图片到对象存储并自动回写至用户资料")
    @PostMapping("/avatar")
    public ProfileResponse uploadAvatar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file
    ) {
        return profileService.uploadAvatar(jwt, file);
    }
}
