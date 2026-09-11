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
        List<ChatMessage> chatHistory
) {
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
