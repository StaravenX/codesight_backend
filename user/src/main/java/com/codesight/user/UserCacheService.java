package com.codesight.user;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 用户基础画像缓存服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCacheService {

    private static final String PREFIX = "user:base:";
    private static final String EMPTY = "{}";

    private final Cache<Long, UserBaseInfo> userBaseLocalCache;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserMapper userMapper;

    /**
     * 获取用户基础画像
     *
     * @param userId 用户 ID
     * @return 基础画像信息
     */
    public UserBaseInfo getUserBaseInfo(Long userId) {
        if (userId == null) {
            return null;
        }
        return batchGetUserBaseInfo(List.of(userId)).get(userId);
    }

    /**
     * 批量获取用户基础画像（L1 本地缓存 -> L2 Redis -> L3 数据库回填）
     *
     * @param userIds 用户 ID 列表
     * @return Map<userId, UserBaseInfo>
     */
    public Map<Long, UserBaseInfo> batchGetUserBaseInfo(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> targetIds = userIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (targetIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 1. 查询本地 Caffeine 缓存
        Map<Long, UserBaseInfo> result = new HashMap<>(userBaseLocalCache.getAllPresent(targetIds));
        List<Long> l1Miss = targetIds.stream()
                .filter(id -> !result.containsKey(id))
                .toList();
        if (l1Miss.isEmpty()) {
            return result;
        }

        // 2. 批量查询 Redis 缓存
        List<String> keys = l1Miss.stream().map(id -> PREFIX + id).toList();
        List<String> vals = stringRedisTemplate.opsForValue().multiGet(keys);
        List<Long> l2Miss = new ArrayList<>();

        if (vals != null && !vals.isEmpty()) {
            for (int i = 0; i < l1Miss.size(); i++) {
                Long id = l1Miss.get(i);
                String val = i < vals.size() ? vals.get(i) : null;
                if (val != null) {
                    if (!EMPTY.equals(val)) {
                        UserBaseInfo info = JSONUtil.toBean(val, UserBaseInfo.class);
                        result.put(id, info);
                        userBaseLocalCache.put(id, info);
                    }
                } else {
                    l2Miss.add(id);
                }
            }
        } else {
            l2Miss.addAll(l1Miss);
        }

        // 3. 从数据库中查询并回填
        if (!l2Miss.isEmpty()) {
            List<User> dbUsers = userMapper.selectList(
                    new LambdaQueryWrapper<User>()
                            .select(User::getId, User::getNickname, User::getAvatar, User::getBio, User::getJobTitle, User::getCompany)
                            .in(User::getId, l2Miss)
            );
            Map<Long, User> dbMap = dbUsers != null
                    ? dbUsers.stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a))
                    : Collections.emptyMap();

            for (Long id : l2Miss) {
                User u = dbMap.get(id);
                if (u != null) {
                    UserBaseInfo info = UserBaseInfo.from(u);
                    result.put(id, info);
                    userBaseLocalCache.put(id, info);
                    stringRedisTemplate.opsForValue().set(PREFIX + id, JSONUtil.toJsonStr(info), 86400L, TimeUnit.SECONDS);
                } else {
                    // 缓存空数据防穿透
                    stringRedisTemplate.opsForValue().set(PREFIX + id, EMPTY, 300L, TimeUnit.SECONDS);
                }
            }
        }
        return result;
    }

    /**
     * 淘汰用户基础画像缓存
     *
     * @param userId 用户 ID
     */
    public void evictUser(Long userId) {
        if (userId == null) {
            return;
        }
        userBaseLocalCache.invalidate(userId);
        stringRedisTemplate.delete(PREFIX + userId);
    }
}
