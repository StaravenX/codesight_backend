package com.codesight.relation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.counter.event.CounterEvent;
import com.codesight.counter.event.CounterEventProducer;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.counter.service.CounterService;
import com.codesight.relation.api.dto.request.FollowListQueryRequest;
import com.codesight.relation.api.dto.response.FollowUserItemResponse;
import com.codesight.relation.api.dto.response.RelationCursorPageResponse;
import com.codesight.relation.api.dto.response.RelationStatusResponse;
import com.codesight.relation.mapper.UserFollowerMapper;
import com.codesight.relation.mapper.UserFollowingMapper;
import com.codesight.relation.model.UserFollower;
import com.codesight.relation.model.UserFollowing;
import com.codesight.relation.util.RelationCursor;
import com.codesight.user.UserBaseInfo;
import com.codesight.user.UserCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 用户关系核心服务类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationService {

    /**
     * 单用户关注上限
     */
    private static final int MAX_FOLLOWING_LIMIT = 5000;

    private final UserFollowingMapper userFollowingMapper;
    private final UserFollowerMapper userFollowerMapper;
    private final UserCacheService userCacheService;
    private final RelationCacheService relationCacheService;
    private final CounterService counterService;
    private final CounterEventProducer counterEventProducer;

    /**
     * 发起关注
     *
     * @param fromUserId 发起关注者 ID
     * @param toUserId   被关注者 ID
     * @return 关注是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean follow(Long fromUserId, Long toUserId) {
        if (fromUserId.equals(toUserId)) {
            throw new BusinessException(ErrorCode.CANNOT_FOLLOW_SELF);
        }

        // 1. 检查缓存判定是否已关注
        boolean isFollowing = relationCacheService.isFollowing(fromUserId, toUserId);
        if (isFollowing) {
            return true;
        }

        // 2. 检查关注上限
        Long activeFollowingCount = userFollowingMapper.selectCount(
                new LambdaQueryWrapper<UserFollowing>()
                        .eq(UserFollowing::getFromUserId, fromUserId)
        );
        if (activeFollowingCount != null && activeFollowingCount >= MAX_FOLLOWING_LIMIT) {
            throw new BusinessException(ErrorCode.FOLLOW_LIMIT_EXCEEDED);
        }

        // 本地事务原子物理双写关注表与粉丝表
        UserFollowing following = UserFollowing.builder()
                .fromUserId(fromUserId)
                .toUserId(toUserId)
                .build();
        UserFollower follower = UserFollower.builder()
                .toUserId(toUserId)
                .fromUserId(fromUserId)
                .build();

        userFollowingMapper.insert(following);
        userFollowerMapper.insert(follower);

        // 同步缓存
        relationCacheService.addFollowing(fromUserId, toUserId);

        // 异步更新双向计数（被关注者+1粉丝，发起者+1关注）
        publishCounterEvents(fromUserId, toUserId, 1);

        return true;
    }

    /**
     * 取消关注
     *
     * @param fromUserId 发起取关者 ID
     * @param toUserId   被取关者 ID
     * @return 操作是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean unfollow(Long fromUserId, Long toUserId) {
        if (fromUserId.equals(toUserId)) {
            return true;
        }

        boolean isFollowing = relationCacheService.isFollowing(fromUserId, toUserId);
        if (!isFollowing) {
            return true;
        }

        int deletedRows = userFollowingMapper.delete(
                new LambdaQueryWrapper<UserFollowing>()
                        .eq(UserFollowing::getFromUserId, fromUserId)
                        .eq(UserFollowing::getToUserId, toUserId)
        );
        userFollowerMapper.delete(
                new LambdaQueryWrapper<UserFollower>()
                        .eq(UserFollower::getToUserId, toUserId)
                        .eq(UserFollower::getFromUserId, fromUserId)
        );

        // 判断是否真实删除，防止重复发送消息
        if (deletedRows > 0) {
            relationCacheService.removeFollowing(fromUserId, toUserId);

            // 异步更新双向计数（被关注者-1粉丝，发起者-1关注）
            publishCounterEvents(fromUserId, toUserId, -1);
        }

        return true;
    }

    /**
     * 异步发布关注双方计数变更事件
     */
    private void publishCounterEvents(Long fromUserId, Long toUserId, int delta) {
        counterEventProducer.publish(CounterEvent.of(
                CounterSchema.EntityType.USER,
                String.valueOf(toUserId),
                CounterSchema.UserMetric.FOLLOWERS.getCode(),
                CounterSchema.UserMetric.FOLLOWERS.getIndex(),
                fromUserId,
                delta
        ));

        counterEventProducer.publish(CounterEvent.of(
                CounterSchema.EntityType.USER,
                String.valueOf(fromUserId),
                CounterSchema.UserMetric.FOLLOWINGS.getCode(),
                CounterSchema.UserMetric.FOLLOWINGS.getIndex(),
                fromUserId,
                delta
        ));
    }

    /**
     * 查询当前用户与目标用户的关系
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetUserId  目标用户 ID
     * @return 关系视图模型
     */
    public RelationStatusResponse getRelationStatus(Long currentUserId, Long targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            return new RelationStatusResponse(false, false);
        }

        boolean following = relationCacheService.isFollowing(currentUserId, targetUserId);
        boolean followedBy = relationCacheService.isFollowing(targetUserId, currentUserId);

        return new RelationStatusResponse(following, followedBy);
    }

    /**
     * 批量查询当前用户对一组目标作者的关注状态
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetUserIds 目标用户 ID 列表
     * @return Map<targetUserId, isFollowing>
     */
    public Map<Long, Boolean> batchGetRelationStatus(Long currentUserId, List<Long> targetUserIds) {
        return relationCacheService.batchGetRelationStatus(currentUserId, targetUserIds);
    }

    /**
     * 游标分页查询用户的关注列表
     *
     * @param request       游标分页查询请求参数
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 游标分页卡片响应体
     */
    public RelationCursorPageResponse<FollowUserItemResponse> listFollowing(
            FollowListQueryRequest request, Long currentUserId) {
        int pageSize = request.limit();

        LambdaQueryWrapper<UserFollowing> qw = new LambdaQueryWrapper<UserFollowing>()
                .select(UserFollowing::getToUserId, UserFollowing::getCreatedTime)
                .eq(UserFollowing::getFromUserId, request.userId());

        RelationCursor.applyPagination(qw, request.cursor(), pageSize, UserFollowing::getCreatedTime, UserFollowing::getToUserId);

        List<UserFollowing> records = userFollowingMapper.selectList(qw);
        if (records.isEmpty()) {
            return RelationCursorPageResponse.<FollowUserItemResponse>builder()
                    .items(List.of())
                    .hasMore(false)
                    .build();
        }

        boolean hasMore = records.size() > pageSize;
        List<UserFollowing> pageRecords = hasMore ? records.subList(0, pageSize) : records;
        List<Long> targetUserIds = pageRecords.stream().map(UserFollowing::getToUserId).toList();

        // 组装卡片基础资料、计数与互动态
        List<FollowUserItemResponse> items = assembleUserItems(targetUserIds, currentUserId);

        String nextCursor = hasMore
                ? RelationCursor.encode(pageRecords.getLast().getCreatedTime(), pageRecords.getLast().getToUserId())
                : null;

        return RelationCursorPageResponse.<FollowUserItemResponse>builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    /**
     * 游标分页查询用户的粉丝列表
     *
     * @param request       游标分页查询请求参数
     * @param currentUserId 当前登录用户 ID（可为空）
     * @return 游标分页卡片响应体
     */
    public RelationCursorPageResponse<FollowUserItemResponse> listFollowers(
            FollowListQueryRequest request, Long currentUserId) {
        int pageSize = request.limit();

        LambdaQueryWrapper<UserFollower> qw = new LambdaQueryWrapper<UserFollower>()
                .select(UserFollower::getFromUserId, UserFollower::getCreatedTime)
                .eq(UserFollower::getToUserId, request.userId());

        RelationCursor.applyPagination(qw, request.cursor(), pageSize, UserFollower::getCreatedTime, UserFollower::getFromUserId);

        List<UserFollower> records = userFollowerMapper.selectList(qw);
        if (records.isEmpty()) {
            return RelationCursorPageResponse.<FollowUserItemResponse>builder()
                    .items(List.of())
                    .hasMore(false)
                    .build();
        }

        boolean hasMore = records.size() > pageSize;
        List<UserFollower> pageRecords = hasMore ? records.subList(0, pageSize) : records;
        List<Long> targetUserIds = pageRecords.stream().map(UserFollower::getFromUserId).toList();

        List<FollowUserItemResponse> items = assembleUserItems(targetUserIds, currentUserId);

        String nextCursor = hasMore
                ? RelationCursor.encode(pageRecords.getLast().getCreatedTime(), pageRecords.getLast().getFromUserId())
                : null;

        return RelationCursorPageResponse.<FollowUserItemResponse>builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    /**
     * 多中台数据组装
     *
     * @param targetUserIds 目标用户 ID 列表
     * @param currentUserId 当前登录用户 ID
     * @return 组装后的卡片列表
     */
    private List<FollowUserItemResponse> assembleUserItems(List<Long> targetUserIds, Long currentUserId) {
        // 1. 批量拉取用户基础画像
        Map<Long, UserBaseInfo> userMap = userCacheService.batchGetUserBaseInfo(targetUserIds);

        // 2. 批量拉取 SDS 二进制计数
        List<String> targetUserIdStrs = targetUserIds.stream().map(String::valueOf).toList();
        Map<String, Map<CounterSchema.MetricItem, Long>> countsMap = counterService.batchGetCounts(
                CounterSchema.EntityType.USER, targetUserIdStrs
        );

        // 3. 批量拉取当前用户与目标用户的关注状态
        Map<Long, Boolean> relationStatusMap = (currentUserId != null)
                ? relationCacheService.batchGetRelationStatus(currentUserId, targetUserIds)
                : Collections.emptyMap();

        // 4. 按原始分页顺序装配 DTO
        List<FollowUserItemResponse> items = new ArrayList<>(targetUserIds.size());
        for (Long targetId : targetUserIds) {
            UserBaseInfo user = userMap.get(targetId);
            Map<CounterSchema.MetricItem, Long> metrics = countsMap.get(String.valueOf(targetId));

            long followerCount = (metrics != null) ? metrics.getOrDefault(CounterSchema.UserMetric.FOLLOWERS, 0L) : 0L;
            long followingCount = (metrics != null) ? metrics.getOrDefault(CounterSchema.UserMetric.FOLLOWINGS, 0L) : 0L;
            boolean followedByMe = Boolean.TRUE.equals(relationStatusMap.get(targetId));

            items.add(FollowUserItemResponse.builder()
                    .userId(targetId)
                    .nickname(user != null ? user.nickname() : null)
                    .avatar(user != null ? user.avatar() : null)
                    .followerCount(followerCount)
                    .followingCount(followingCount)
                    .followedByMe(followedByMe)
                    .build());
        }

        return items;
    }
}
