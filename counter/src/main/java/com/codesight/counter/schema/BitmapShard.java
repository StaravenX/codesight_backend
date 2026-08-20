package com.codesight.counter.schema;

import lombok.experimental.UtilityClass;

/**
 * 位图分片配置与帮助函数。
 * 采用固定分片大小，避免单键因用户ID偏移过大而膨胀。
 */
@UtilityClass
public final class BitmapShard {
    public static final int CHUNK_SIZE = 32_768;

    /**
     * 计算用户 ID 对应的分片索引。
     *
     * @param userId 用户 ID
     * @return 分片索引
     */
    public static long chunkOf(long userId) {
        return userId / CHUNK_SIZE;
    }

    /**
     * 计算用户 ID 在所属分片内的位偏移量。
     *
     * @param userId 用户 ID
     * @return 分片内位偏移量（0 ~ CHUNK_SIZE-1）
     */
    public static long bitOf(long userId) {
        return userId % CHUNK_SIZE;
    }
}
