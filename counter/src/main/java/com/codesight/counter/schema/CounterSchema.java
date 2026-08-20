package com.codesight.counter.schema;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;

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
     * 统一指标契约接口
     */
    public interface MetricItem {
        int getIndex();
        String getCode();

        default int offset() {
            return getIndex() * FIELD_SIZE;
        }
    }

    /**
     * 文章/内容维度核心互动指标枚举
     */
    @Getter
    @RequiredArgsConstructor
    public enum Metric implements MetricItem {
        VIEWS(0, "views"),
        LIKE(1, "like"),
        COMMENT(2, "comment"),
        FAVORITE(3, "favorite");

        private final int index;
        private final String code;
    }

    /**
     * 创作者/博主维度核心画像指标枚举
     */
    @Getter
    @RequiredArgsConstructor
    public enum UserMetric implements MetricItem {
        VIEWS_RECEIVED(0, "viewsReceived"),
        LIKES_RECEIVED(1, "likesReceived"),
        FOLLOWERS(2, "followers"),
        FOLLOWINGS(3, "followings");

        private final int index;
        private final String code;
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
}
