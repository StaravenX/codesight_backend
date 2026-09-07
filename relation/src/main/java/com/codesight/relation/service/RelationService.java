package com.codesight.relation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.counter.event.CounterEvent;
import com.codesight.counter.event.CounterEventProducer;
import com.codesight.counter.schema.CounterSchema;
import com.codesight.relation.api.dto.response.RelationStatusResponse;
import com.codesight.relation.mapper.UserFollowerMapper;
import com.codesight.relation.mapper.UserFollowingMapper;
import com.codesight.relation.model.UserFollower;
import com.codesight.relation.model.UserFollowing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

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
    private final RelationCacheService relationCacheService;
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

        // 被关注者增加 1 个粉丝
        counterEventProducer.publish(CounterEvent.of(
                CounterSchema.EntityType.USER,
                String.valueOf(toUserId),
                CounterSchema.UserMetric.FOLLOWERS.getCode(),
                CounterSchema.UserMetric.FOLLOWERS.getIndex(),
                fromUserId,
                1
        ));

        // 发起关注者增加 1 个关注
        counterEventProducer.publish(CounterEvent.of(
                CounterSchema.EntityType.USER,
                String.valueOf(fromUserId),
                CounterSchema.UserMetric.FOLLOWINGS.getCode(),
                CounterSchema.UserMetric.FOLLOWINGS.getIndex(),
                fromUserId,
                1
        ));

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

            // 被关注者扣减 1 个粉丝
            counterEventProducer.publish(CounterEvent.of(
                    CounterSchema.EntityType.USER,
                    String.valueOf(toUserId),
                    CounterSchema.UserMetric.FOLLOWERS.getCode(),
                    CounterSchema.UserMetric.FOLLOWERS.getIndex(),
                    fromUserId,
                    -1
            ));

            // 发起关注者扣减 1 个关注
            counterEventProducer.publish(CounterEvent.of(
                    CounterSchema.EntityType.USER,
                    String.valueOf(fromUserId),
                    CounterSchema.UserMetric.FOLLOWINGS.getCode(),
                    CounterSchema.UserMetric.FOLLOWINGS.getIndex(),
                    fromUserId,
                    -1
            ));
        }

        return true;
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
}
