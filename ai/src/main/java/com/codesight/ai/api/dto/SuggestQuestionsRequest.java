package com.codesight.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

/**
 * 智能技术追问建议请求体
 */
@Builder
@Schema(description = "智能技术追问建议请求体")
public record SuggestQuestionsRequest(
        @Schema(description = "多轮历史消息列表")
        List<AiChatRequest.ChatMessage> chatHistory,

        @Schema(description = "文章上下文信息")
        AiChatRequest.ArticleContext articleContext
) {}
