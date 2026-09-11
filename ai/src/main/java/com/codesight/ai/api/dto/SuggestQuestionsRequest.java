package com.codesight.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;

/**
 * 智能技术追问建议请求体
 */
@Builder
@Schema(description = "智能技术追问建议请求体")
public record SuggestQuestionsRequest(
        @NotNull(message = "文章ID不能为空")
        @Schema(description = "当前正在阅读的文章 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        Long articleId,

        @Schema(description = "多轮历史消息列表")
        List<AiChatRequest.ChatMessage> chatHistory
) {}
