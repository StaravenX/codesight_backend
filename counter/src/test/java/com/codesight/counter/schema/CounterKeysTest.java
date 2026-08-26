package com.codesight.counter.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("CounterKeys Key 命名空间规范单测")
class CounterKeysTest {

    @Test
    @DisplayName("测试各类 Redis Key 格式契约")
    void testKeyFormats() {
        assertEquals("bm:article:1001:like:0", CounterKeys.bitmapKey(CounterSchema.EntityType.ARTICLE, "1001", "like", 0));
        assertEquals("cnt:v1:article:1001", CounterKeys.sdsKey(CounterSchema.EntityType.ARTICLE, "1001"));
        assertEquals("agg:v1:article:1001", CounterKeys.aggKey(CounterSchema.EntityType.ARTICLE, "1001"));
        assertEquals("lock:sds-rebuild:article:1001", CounterKeys.rebuildLockKey(CounterSchema.EntityType.ARTICLE, "1001"));

        // 用户维度
        assertEquals("bm:user:2002:followers:1", CounterKeys.bitmapKey(CounterSchema.EntityType.USER, "2002", "followers", 1));
        assertEquals("cnt:v1:user:2002", CounterKeys.sdsKey(CounterSchema.EntityType.USER, "2002"));
    }
}
