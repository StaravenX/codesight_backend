package com.codesight.counter.schema;

import lombok.experimental.UtilityClass;

/**
 * 计数系统 Redis Key 生成与统一收敛工具。
 */
@UtilityClass
public class CounterKeys {

    /**
     * 实体维度 16 字节定长 SDS 快照键：cnt:v1:{entityType}:{entityId}
     */
    public static String sdsKey(String entityType, String entityId) {
        return String.format("cnt:%s:%s:%s", CounterSchema.SCHEMA_ID, entityType, entityId);
    }

    /**
     * 4KB 分片位图事实判重层键：bm:{metric}:{entityType}:{entityId}:{chunk}
     */
    public static String bitmapKey(String metric, String entityType, String entityId, long chunk) {
        return String.format("bm:%s:%s:%s:%d", metric, entityType, entityId, chunk);
    }

    /**
     * 1秒写聚合削峰暂存桶（Hash）：agg:v1:{entityType}:{entityId}
     */
    public static String aggKey(String entityType, String entityId) {
        return String.format("agg:%s:%s:%s", CounterSchema.SCHEMA_ID, entityType, entityId);
    }
}