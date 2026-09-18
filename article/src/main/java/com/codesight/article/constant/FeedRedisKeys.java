package com.codesight.article.constant;

import lombok.experimental.UtilityClass;

/**
 * 关注流 (Timeline) Redis 键与容量策略定义
 */
@UtilityClass
public class FeedRedisKeys {

    /**
     * 粉丝收件箱 Key 前缀（ZSET：Score = 发布时间戳毫秒, Member = 文章 ID）
     */
    public static final String INBOX_PREFIX = "feed:inbox:";

    /**
     * 作者发件箱 Key 前缀（ZSET：Score = 发布时间戳毫秒, Member = 文章 ID）
     */
    public static final String OUTBOX_PREFIX = "feed:outbox:";


    /**
     * 大 V 状态机集合 Redis Key
     */
    public static final String BIG_V_SET_KEY = "feed:big_v:authors";

    /**
     * 用户推荐流已读曝光 Key 前缀
     */
    public static final String EXPOSED_PREFIX = "feed:user:exposed:";

    /**
     * 获取粉丝个人收件箱 Redis Key
     *
     * @param userId 粉丝用户 ID
     * @return Redis Key
     */
    public static String getInboxKey(Long userId) {
        return INBOX_PREFIX + userId;
    }

    /**
     * 获取创作者个人发件箱 Redis Key
     *
     * @param authorId 创作者用户 ID
     * @return Redis Key
     */
    public static String getOutboxKey(Long authorId) {
        return OUTBOX_PREFIX + authorId;
    }

    /**
     * 获取用户推荐流已读曝光 Redis Key
     *
     * @param userId 用户 ID
     * @return Redis Key
     */
    public static String getExposedKey(Long userId) {
        return EXPOSED_PREFIX + userId;
    }
}
