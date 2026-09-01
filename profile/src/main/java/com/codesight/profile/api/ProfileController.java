package com.codesight.profile.api;

import com.codesight.common.annotation.CurrentUserId;
import com.codesight.common.annotation.RateLimit;
import com.codesight.profile.api.dto.AuthorCardResponse;
import com.codesight.profile.api.dto.ProfilePatchRequest;
import com.codesight.profile.api.dto.ProfileResponse;
import com.codesight.profile.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人资料接口控制器
 * <p>
 * 提供当前登录用户的个人资料查询、局部更新、头像直传写入以及创作者名片等接口。
 * 控制器直接返回业务响应对象，由 GlobalResponseAdvice 统一包装为 Result。
 */
@Tag(name = "个人资料接口", description = "提供当前登录用户的资料查询、修改、头像更新与创作者名片接口")
@RestController
@RequestMapping("/api/v1/profile")
@Validated
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /**
     * 更新个人资料（支持部分字段 PATCH 局部更新）
     *
     * @param userId  当前登录用户 ID
     * @param request 待更新字段请求体
     * @return 更新后的个人资料快照（由全局拦截器自动包装为 Result）
     */
    @Operation(summary = "修改个人资料", description = "支持对用户资料进行局部更新（PATCH），未传入的字段保持不变")
    @PatchMapping
    @RateLimit(windowSeconds = 60, maxRequests = 300)
    public ProfileResponse patch(
            @Parameter(hidden = true) @CurrentUserId Long userId,
            @Valid @RequestBody ProfilePatchRequest request
    ) {
        return profileService.updateProfile(userId, request);
    }

    /**
     * 上传头像并更新用户头像地址
     *
     * @param userId 当前登录用户 ID
     * @param file   头像文件（multipart/form-data）
     * @return 更新后的个人资料快照（由全局拦截器自动包装为 Result）
     */
    @Operation(summary = "上传用户头像", description = "上传头像图片到对象存储并自动回写至用户资料")
    @PostMapping("/avatar")
    @RateLimit(windowSeconds = 60, maxRequests = 300)
    public ProfileResponse uploadAvatar(
            @Parameter(hidden = true) @CurrentUserId Long userId,
            @RequestPart("file") MultipartFile file
    ) {
        return profileService.uploadAvatar(userId, file);
    }

    /**
     * 获取当前登录用户个人资料
     *
     * @param userId 当前登录用户 ID
     * @return 个人资料响应
     */
    @Operation(summary = "获取当前用户信息", description = "基于认证上下文返回当前登录用户的完整个人资料")
    @GetMapping("/me")
    @RateLimit(windowSeconds = 60, maxRequests = 300)
    public ProfileResponse me(@Parameter(hidden = true) @CurrentUserId Long userId) {
        return profileService.getProfile(userId);
    }

    /**
     * 获取创作者公开名片（包含基础资料、16B SDS 获赞/阅读/粉丝计数及关注状态）
     *
     * @param authorId      作者用户 ID
     * @param currentUserId 当前登录用户 ID（未登录为 null）
     * @return 创作者名片响应体
     */
    @Operation(summary = "获取创作者名片", description = "获取作者基础资料与 16B SDS 互动计数（总阅读/获赞/粉丝）及当前用户关注状态")
    @GetMapping("/authors/{authorId}/")
    @RateLimit(windowSeconds = 60, maxRequests = 300)
    public AuthorCardResponse getAuthorCard(
            @PathVariable Long authorId,
            @Parameter(hidden = true) @CurrentUserId(required = false) Long currentUserId
    ) {
        return profileService.getAuthorCard(authorId, currentUserId);
    }
}
