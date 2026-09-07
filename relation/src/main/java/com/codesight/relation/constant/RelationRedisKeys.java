package com.codesight.relation.constant;

import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 关系模块 Redis 键与 TTL 策略定义
 */
@UtilityClass
public class RelationRedisKeys {

    /**
     * 用户关注集合 Key
     */
    public static final String FOLLOWING_SET_PREFIX = "relation:following:";

    /**
     * 空值防穿透元素
     */
    public static final String EMPTY_SENTINEL = "-1";

    /**
     * 生成用户关注集合 Redis Key
     *
     * @param userId 发起关注的用户 ID
     * @return Redis Key
     */
    public static String getFollowingKey(Long userId) {
        return FOLLOWING_SET_PREFIX + userId;
    }

    /**
     * 基础 TTL: 24 小时，Jitter: 0 ~ 4 小时
     *
     * @return Duration
     */
    public static Duration getRandomizedTtl() {
        long jitterSeconds = ThreadLocalRandom.current().nextLong(0, 14400); // 0~4小时
        return Duration.ofHours(24).plusSeconds(jitterSeconds);
    }
}
