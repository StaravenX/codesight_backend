package com.codesight.counter.schema;

import com.codesight.counter.service.CounterService;
import java.util.Map;

/**
 * 实体计数自愈重建策略接口。
 * <p>
 * 各业务领域按需实现专属的重建方法，供计数业务层 {@link CounterService}调用。
 */
public interface CounterRebuilder {

    /**
     * 当前策略支持的业务实体类型 (如 "article", "user", "comment")
     *
     * @return 业务实体类型字符串
     */
    String entityType();

    /**
     * 执行具体实体的真值重建计算
     *
     * @param entityId 实体全局唯一标识
     * @return 该实体各个指标的代码与最新真值 Map (如 {"views": 50000L, "like": 1200L, ...})
     */
    Map<String, Long> rebuild(String entityId);
}
