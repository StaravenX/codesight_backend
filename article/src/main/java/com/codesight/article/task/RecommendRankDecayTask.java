package com.codesight.article.task;

import com.codesight.article.service.RecommendRankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 推荐候选池定时降温任务
 * <p>
 * 职责：
 * 1. 每 15 分钟对 Redis 推荐候选池执行原子 Lua 衰减
 * 2. 淘汰低分文章并截断容量（默认保持在 Top-3000）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendRankDecayTask {

    private final RecommendRankService recommendRankService;

    @Scheduled(initialDelay = 60_000L, fixedRate = 15 * 60_000L)
    public void executeDecay() {
        recommendRankService.decayAll(
                RecommendRankService.DEFAULT_DECAY_FACTOR,
                RecommendRankService.DEFAULT_MAX_CAPACITY
        );
    }
}
