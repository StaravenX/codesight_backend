package com.codesight.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;

/**
 * AI 文章伴读问答请求体
 */
@Builder
@Schema(description = "AI 文章伴读问答请求体")
public record AiChatRequest(
        @NotNull(message = "文章ID不能为空")
        @Schema(description = "当前正在阅读的文章 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        Long articleId,

        @NotBlank(message = "问题内容不能为空")
        @Schema(description = "问题内容", requiredMode = Schema.RequiredMode.REQUIRED)
        String question,

        @Schema(description = "多轮对话历史消息列表")
        List<ChatMessage> chatHistory,

        @Schema(description = "文章上下文信息")
        ArticleContext articleContext
) {
    /**
     * 文章上下文直传载荷
     */
    @Builder
    @Schema(description = "文章上下文直传载荷")
    public record ArticleContext(
            @Schema(description = "文章标题")
            String title,

            @Schema(description = "文章摘要")
            String summary,

            @Schema(description = "文章标签列表")
            List<String> tags,

            @Schema(description = "文章正文内容")
            String content
    ) {}

    /**
     * 多轮对话单条消息体
     */
    @Builder
    @Schema(description = "多轮对话单条消息体")
    public record ChatMessage(
            @Schema(description = "角色: user / assistant")
            String role,

            @Schema(description = "消息内容")
            String content
    ) {}
}
