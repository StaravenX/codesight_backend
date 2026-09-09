package com.codesight.relation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codesight.relation.constant.RelationRedisKeys;
import com.codesight.relation.mapper.UserFollowingMapper;
import com.codesight.relation.model.UserFollowing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 用户关系缓存服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final UserFollowingMapper userFollowingMapper;

    /**
     * 判断发起者是否关注了目标用户
     *
     * @param fromUserId 发起者 ID
     * @param toUserId   目标用户 ID
     * @return true=已关注，false=未关注
     */
    public boolean isFollowing(Long fromUserId, Long toUserId) {
        ensureFollowingLoaded(fromUserId);
        String key = RelationRedisKeys.getFollowingKey(fromUserId);
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, String.valueOf(toUserId));
        return Boolean.TRUE.equals(isMember);
    }

    /**
     * 批量查询当前用户对一组目标用户的关注状态
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetUserIds 目标用户 ID 列表
     * @return Map<targetUserId, isFollowing>
     */
    public Map<Long, Boolean> batchGetRelationStatus(Long currentUserId, List<Long> targetUserIds) {
        // 目标用户去重并过滤自身
        List<Long> distinctTargets = targetUserIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (distinctTargets.isEmpty()) {
            return Collections.emptyMap();
        }

        ensureFollowingLoaded(currentUserId);
        String key = RelationRedisKeys.getFollowingKey(currentUserId);

        List<Object> pipelineResults = stringRedisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(@NonNull RedisOperations operations) throws DataAccessException {
                for (Long targetId : distinctTargets) {
                    if (currentUserId.equals(targetId)) {
                        continue;
                    }
                    operations.opsForSet().isMember(key, String.valueOf(targetId));
                }
                return null;
            }
        });

        return createStatusMap(currentUserId, distinctTargets, pipelineResults);
    }

    private static Map<Long, Boolean> createStatusMap(Long currentUserId, List<Long> distinctTargets, List<Object> pipelineResults) {
        Map<Long, Boolean> statusMap = new HashMap<>(distinctTargets.size());
        int resultIndex = 0;
        for (Long targetId : distinctTargets) {
            if (currentUserId.equals(targetId)) {
                statusMap.put(targetId, false);
            } else if (resultIndex < pipelineResults.size()) {
                Object res = pipelineResults.get(resultIndex++);
                statusMap.put(targetId, Boolean.TRUE.equals(res));
            } else {
                statusMap.put(targetId, false);
            }
        }
        return statusMap;
    }

    /**
     * 同步向关注缓存集合添加关注项
     *
     * @param fromUserId 发起者 ID
     * @param toUserId   目标用户 ID
     */
    public void addFollowing(Long fromUserId, Long toUserId) {
        String key = RelationRedisKeys.getFollowingKey(fromUserId);
        if (stringRedisTemplate.hasKey(key)) {
            stringRedisTemplate.opsForSet().remove(key, RelationRedisKeys.EMPTY_SENTINEL);
            stringRedisTemplate.opsForSet().add(key, String.valueOf(toUserId));
        }
    }

    /**
     * 同步从关注缓存集合移除关注项
     *
     * @param fromUserId 发起者 ID
     * @param toUserId   目标用户 ID
     */
    public void removeFollowing(Long fromUserId, Long toUserId) {
        String key = RelationRedisKeys.getFollowingKey(fromUserId);
        if (stringRedisTemplate.hasKey(key)) {
            stringRedisTemplate.opsForSet().remove(key, String.valueOf(toUserId));
        }
    }

    /**
     * 确保用户的关注列表已加载到 Redis Set 中
     *
     * @param fromUserId 发起者 ID
     */
    public void ensureFollowingLoaded(Long fromUserId) {
        String key = RelationRedisKeys.getFollowingKey(fromUserId);
        if (stringRedisTemplate.hasKey(key)) {
            return;
        }

        // 从数据库中查询并回填
        List<UserFollowing> activeFollowings = userFollowingMapper.selectList(
                new LambdaQueryWrapper<UserFollowing>()
                        .select(UserFollowing::getToUserId)
                        .eq(UserFollowing::getFromUserId, fromUserId)
                        .orderByDesc(UserFollowing::getCreatedTime)
        );
        if (activeFollowings != null && !activeFollowings.isEmpty()) {
            String[] toUserIdsStr = activeFollowings.stream()
                    .map(item -> String.valueOf(item.getToUserId()))
                    .toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(key, toUserIdsStr);
        } else {
            // 缓存空数据
            stringRedisTemplate.opsForSet().add(key, RelationRedisKeys.EMPTY_SENTINEL);
        }

        // 设置随机打散的 TTL
        stringRedisTemplate.expire(key, RelationRedisKeys.getRandomizedTtl());
    }

    /**
     * 获取用户关注的所有目标用户 ID 集合
     *
     * @param fromUserId 发起者 ID
     * @return 关注的目标用户 ID 集合
     */
    public Set<Long> getFollowingUserIds(Long fromUserId) {
        if (fromUserId == null) {
            return Collections.emptySet();
        }
        ensureFollowingLoaded(fromUserId);
        String key = RelationRedisKeys.getFollowingKey(fromUserId);
        Set<String> members = stringRedisTemplate.opsForSet().members(key);
        if (members == null || members.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> result = new HashSet<>(members.size());
        for (String member : members) {
            if (!RelationRedisKeys.EMPTY_SENTINEL.equals(member)) {
                try {
                    result.add(Long.parseLong(member));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }
}
