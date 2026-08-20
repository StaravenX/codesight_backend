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
     * 4KB 分片位图事实判重层键：bm:{entityType}:{entityId}:{metric}:{chunk}
     */
    public static String bitmapKey(String entityType, String entityId, String metric, long chunk) {
        return String.format("bm:%s:%s:%s:%d", entityType, entityId, metric, chunk);
    }

    /**
     * 1秒写聚合削峰暂存桶（Hash）：agg:v1:{entityType}:{entityId}
     */
    public static String aggKey(String entityType, String entityId) {
        return String.format("agg:%s:%s:%s", CounterSchema.SCHEMA_ID, entityType, entityId);
    }

    /**
     * SDS 自愈重建分布式锁键：lock:sds-rebuild:{entityType}:{entityId}
     */
    public static String rebuildLockKey(String entityType, String entityId) {
        return String.format("lock:sds-rebuild:%s:%s", entityType, entityId);
    }
}