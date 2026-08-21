package com.codesight.counter.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("BitmapShard 分片算法单测")
class BitmapShardTest {

    @Test
    @DisplayName("测试用户 ID 映射分片 chunk 与位偏移 bitOf")
    void testChunkAndBitCalculation() {
        // 边界 1：第一个分片的第 0 位
        assertEquals(0L, BitmapShard.chunkOf(0L));
        assertEquals(0L, BitmapShard.bitOf(0L));

        // 边界 2：第一个分片的最后一位 (32767 = 4KB * 8 - 1)
        assertEquals(0L, BitmapShard.chunkOf(32767L));
        assertEquals(32767L, BitmapShard.bitOf(32767L));

        // 边界 3：跨越到第二个分片的第 0 位 (32768)
        assertEquals(1L, BitmapShard.chunkOf(32768L));
        assertEquals(0L, BitmapShard.bitOf(32768L));

        // 边界 4：大用户 ID
        long userId = 100_000L;
        long expectedChunk = 100_000L / BitmapShard.CHUNK_SIZE; // 3
        long expectedBit = 100_000L % BitmapShard.CHUNK_SIZE;   // 17056
        assertEquals(expectedChunk, BitmapShard.chunkOf(userId));
        assertEquals(expectedBit, BitmapShard.bitOf(userId));
    }
}
