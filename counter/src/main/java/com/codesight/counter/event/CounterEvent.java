package com.codesight.counter.event;

import com.codesight.counter.schema.CounterSchema;

/**
 * 通用计数变更领域事件（不可变 Record）。
 * <p>
 * 架构设计：
 * 统一承载全平台多维度的计数增量变动（如文章互动、创作者资产画像、评论点赞等）。
 *
 * @param entityType 业务实体类型（如 "article" 文章、"user" 创作者、"comment" 评论）
 * @param entityId   业务实体全局唯一标识
 * @param metric     指标代码（如 "like"、"favorite"、"followers"、"viewsReceived"）
 * @param idx        对应实体 Schema 二进制内存字节段下标（0~3）
 * @param userId     用户 ID
 * @param delta      计数值变动增量（正向触发为 +1，反向取消为 -1）
 */
public record CounterEvent(
        CounterSchema.EntityType entityType,
        String entityId,
        String metric,
        int idx,
        long userId,
        int delta
) {
    public static final String TOPIC = "counter-events";

    public static CounterEvent of(CounterSchema.EntityType entityType, String entityId, String metric, int idx, long userId, int delta) {
        return new CounterEvent(entityType, entityId, metric, idx, userId, delta);
    }
}
