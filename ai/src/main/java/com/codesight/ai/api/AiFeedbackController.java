package com.codesight.ai.api;

import com.codesight.ai.service.ArticleVectorService;
import com.codesight.common.annotation.CurrentUserId;
import com.codesight.common.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 推荐治理反馈接口
 */
@Tag(name = "AI 用户反馈与推荐治理")
@RestController
@RequestMapping("/api/v1/ai/feedback")
@RequiredArgsConstructor
public class AiFeedbackController {

    private final ArticleVectorService articleVectorService;

    @RateLimit(maxRequests = 30)
    @Operation(summary = "文章不感兴趣（触发语义负反馈）")
    @PostMapping("/dislike/{articleId}")
    public void dislikeArticle(
            @Parameter(hidden = true) @CurrentUserId Long userId,
            @PathVariable Long articleId) {
        articleVectorService.recordFeedback(ArticleVectorService.FeedbackType.NEGATIVE, userId, articleId);
    }
}