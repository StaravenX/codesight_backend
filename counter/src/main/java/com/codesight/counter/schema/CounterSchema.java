package com.codesight.counter.schema;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 计数系统 Schema 规范与常量定义。
 * <p>
 * 1. 文章维度 SDS 内存排布（16 字节）：
 *    [0~3B: views 阅读数] [4~7B: like 点赞数] [8~11B: comment 评论数] [12~15B: favorite 收藏数]
 * <p>
 * 2. 用户维度 SDS 内存排布（16 字节）：
 *    [0~3B: viewsReceived 获得阅读数] [4~7B: likesReceived 获得点赞数] [8~11B: followers 粉丝数] [12~15B: followings 关注数]
 */
@UtilityClass
public class CounterSchema {

    public static final String SCHEMA_ID = "v1";
    public static final int FIELD_SIZE = 4; // 每个维度的大小(4字节 = 32位)
    public static final int SCHEMA_LEN = 4; // 维度的数量
    public static final int TOTAL_BYTES = FIELD_SIZE * SCHEMA_LEN;

    /**
     * 业务实体类型枚举
     */
    @Getter
    @AllArgsConstructor
    public enum EntityType {
        ARTICLE(ArticleMetric.values()),
        USER(UserMetric.values());

        private final MetricItem[] metrics;
    }

    /**
     * 统一指标契约接口
     */
    public interface MetricItem {
        int getIndex();
        String getCode();

        /**
         * 是否具备 4KB 分片位图事实层（用于区分点赞/关注等状态型指标与浏览/评论等纯计数标量）
         */
        boolean isBitmapBacked();

        default int offset() {
            return getIndex() * FIELD_SIZE;
        }
    }

    /**
     * 文章/内容维度核心互动指标枚举
     */
    @Getter
    @AllArgsConstructor
    public enum ArticleMetric implements MetricItem {
        VIEWS(0, "views", false),
        LIKE(1, "like", true),
        COMMENT(2, "comment", false),
        FAVORITE(3, "favorite", true);

        private final int index;
        private final String code;
        private final boolean bitmapBacked;
    }

    /**
     * 创作者/博主维度核心画像指标枚举
     */
    @Getter
    @AllArgsConstructor
    public enum UserMetric implements MetricItem {
        VIEWS_RECEIVED(0, "viewsReceived", false),
        LIKES_RECEIVED(1, "likesReceived", false),
        FOLLOWERS(2, "followers", true),
        FOLLOWINGS(3, "followings", true);

        private final int index;
        private final String code;
        private final boolean bitmapBacked;
    }

    // ==================== 编解码工具方法 ====================

    /**
     * 以大端序从字节数组指定偏移量读取 32 位无符号整型
     *
     * @param buf 字节数组
     * @param off 起始偏移量
     * @return 无符号数值
     */
    public static long readInt32BE(byte[] buf, int off) {
        if (buf == null || off + FIELD_SIZE > buf.length) {
            return 0L;
        }
        return Integer.toUnsignedLong(ByteBuffer.wrap(buf).getInt(off));
    }

    /**
     * 以大端序将 32 位数值写入字节数组指定偏移量
     *
     * @param buf 目标字节数组
     * @param off 起始偏移量
     * @param val 待写入数值
     */
    public static void writeInt32BE(byte[] buf, int off, long val) {
        if (buf == null || off + FIELD_SIZE > buf.length) {
            return;
        }
        int intVal = (int) Math.clamp(val, 0, 0xFFFF_FFFFL);
        ByteBuffer.wrap(buf).putInt(off, intVal);
    }

    /**
     * 将 16 字节定长二进制 SDS 字节数组解码为强类型指标 Map
     *
     * @param entityType 业务实体类型（如 ARTICLE, USER）
     * @param raw        原始 16 字节数组
     * @return 各指标枚举 -> 数值 Map，若 raw 为空或长度不足 16 字节则返回 null
     */
    public static Map<MetricItem, Long> decodeSds(EntityType entityType, byte[] raw) {
        if (raw == null || raw.length != TOTAL_BYTES || entityType == null) {
            return null;
        }
        Map<MetricItem, Long> result = new HashMap<>();
        for (MetricItem m : entityType.getMetrics()) {
            result.put(m, readInt32BE(raw, m.offset()));
        }
        return result;
    }
}
