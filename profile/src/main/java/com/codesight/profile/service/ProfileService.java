package com.codesight.profile.service;

import com.codesight.auth.token.JwtService;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.profile.api.dto.ProfilePatchRequest;
import com.codesight.profile.api.dto.ProfileResponse;
import com.codesight.storage.service.StorageService;
import com.codesight.user.User;
import com.codesight.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人资料业务服务类
 * <p>
 * 处理用户个人资料的查询、局部字段更新以及头像上传与持久化回写等核心业务。
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserService userService;
    private final StorageService storageService;
    private final JwtService jwtService;

    /**
     * 更新个人资料（支持局部字段 PATCH 更新）
     *
     * @param jwt 当前请求绑定的 JWT 令牌
     * @param request 局部更新请求
     * @return 更新后的最新快照
     */
    @Transactional(rollbackFor = Exception.class)
    public ProfileResponse updateProfile(Jwt jwt, ProfilePatchRequest request) {
        long userId = jwtService.extractUserId(jwt);

        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在");
        }

        if (!request.hasAnyField()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未提交任何更新字段");
        }

        userService.updateById(request.toEntity(userId));

        User updated = userService.getById(userId);
        return ProfileResponse.from(updated);
    }

    /**
     * 上传头像并持久化回写用户资料
     *
     * @param jwt  当前请求绑定的 JWT 令牌
     * @param file 前端上传的头像文件
     * @return 更新后的最新快照
     */
    @Transactional(rollbackFor = Exception.class)
    public ProfileResponse uploadAvatar(Jwt jwt, MultipartFile file) {
        long userId = jwtService.extractUserId(jwt);

        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在");
        }

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件不能为空");
        }

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "avatar.png";
        String objectKey = "avatars/" + userId + "/" + System.currentTimeMillis() + "_" + filename;
        String avatarUrl = storageService.uploadFile(objectKey, file);

        User patch = new User();
        patch.setId(userId);
        patch.setAvatar(avatarUrl);
        userService.updateById(patch);

        User updated = userService.getById(userId);
        return ProfileResponse.from(updated);
    }
}
