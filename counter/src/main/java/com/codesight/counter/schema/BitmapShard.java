package com.codesight.counter.schema;

import lombok.experimental.UtilityClass;

/**
 * 位图分片配置与帮助函数。
 * 采用固定分片大小，避免单键因用户ID偏移过大而膨胀。
 */
@UtilityClass
public final class BitmapShard {
    public static final int CHUNK_SIZE = 32_768;

    public static long chunkOf(long userId) {
        return userId / CHUNK_SIZE;
    }

    public static long bitOf(long userId) {
        return userId % CHUNK_SIZE;
    }
}
