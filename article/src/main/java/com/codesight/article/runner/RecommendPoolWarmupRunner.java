package com.codesight.article.runner;

import com.codesight.article.service.RecommendRankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 推荐候选池冷启动预热
 * <p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendPoolWarmupRunner implements ApplicationRunner {

    private final RecommendRankService recommendRankService;

    @Override
    public void run(ApplicationArguments args) {
        recommendRankService.refillPoolIfLow(
                RecommendRankService.WARMUP_MIN_THRESHOLD,
                RecommendRankService.DEFAULT_MAX_CAPACITY
        );
    }
}
