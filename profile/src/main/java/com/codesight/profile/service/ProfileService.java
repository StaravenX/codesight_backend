package com.codesight.profile.service;

import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.profile.api.dto.AuthorCardResponse;
import com.codesight.profile.api.dto.ProfilePatchRequest;
import com.codesight.profile.api.dto.ProfileResponse;
import com.codesight.storage.service.StorageService;
import com.codesight.user.User;
import com.codesight.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 个人资料业务服务类
 * <p>
 * 处理用户个人资料的查询、局部字段更新、头像上传以及创作者名片多源聚合等核心业务。
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserService userService;
    private final StorageService storageService;
    private final CounterService counterService;

    /**
     * 更新个人资料（支持局部字段 PATCH 更新）
     *
     * @param userId  当前登录用户 ID
     * @param request 局部更新请求
     * @return 更新后的最新快照
     */
    @Transactional(rollbackFor = Exception.class)
    public ProfileResponse updateProfile(long userId, ProfilePatchRequest request) {
        User current = userService.getById(userId);
        if (current == null) {
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
     * @param userId 当前登录用户 ID
     * @param file   前端上传的头像文件
     * @return 更新后的最新快照
     */
    @Transactional(rollbackFor = Exception.class)
    public ProfileResponse uploadAvatar(long userId, MultipartFile file) {
        User current = userService.getById(userId);
        if (current == null) {
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

    /**
     * 获取用户个人资料详情
     *
     * @param userId 当前用户 ID
     * @return 个人资料快照
     */
    public ProfileResponse getProfile(long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在或已被删除");
        }
        return ProfileResponse.from(user);
    }

    /**
     * 获取创作者公开名片卡片（包含基础资料、16B SDS 获赞/阅读/粉丝计数及关注状态）
     *
     * @param authorId      作者用户 ID
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 创作者名片响应体
     */
    public AuthorCardResponse getAuthorCard(Long authorId, Long currentUserId) {
        User author = userService.getById(authorId);
        if (author == null) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "作者不存在或已被删除");
        }

        // 1. 读取 16B SDS 紧凑计数快照
        Map<CounterSchema.MetricItem, Long> counts = counterService.getCounts(
                CounterSchema.EntityType.USER,
                String.valueOf(authorId)
        );

        long viewsReceived = counts.getOrDefault(CounterSchema.UserMetric.VIEWS_RECEIVED, 0L);
        long likesReceived = counts.getOrDefault(CounterSchema.UserMetric.LIKES_RECEIVED, 0L);
        long followerCount = counts.getOrDefault(CounterSchema.UserMetric.FOLLOWERS, 0L);
        long followingCount = counts.getOrDefault(CounterSchema.UserMetric.FOLLOWINGS, 0L);

        // 2. 判定当前登录用户是否已关注该作者（4KB 分片位图）
        boolean isFollowed = currentUserId != null && counterService.isSet(
                        CounterSchema.EntityType.USER,
                        String.valueOf(authorId),
                        CounterSchema.UserMetric.FOLLOWERS,
                        currentUserId
                );

        return new AuthorCardResponse(
                author.getId(),
                author.getNickname(),
                author.getAvatar(),
                author.getBio(),
                author.getJobTitle(),
                author.getCompany(),
                viewsReceived,
                likesReceived,
                followerCount,
                followingCount,
                isFollowed
        );
    }
}
