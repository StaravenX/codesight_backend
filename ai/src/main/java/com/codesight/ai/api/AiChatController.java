package com.codesight.ai.api;

import com.codesight.ai.api.dto.AiChatRequest;
import com.codesight.ai.api.dto.SuggestQuestionsRequest;
import com.codesight.ai.api.dto.SuggestQuestionsResponse;
import com.codesight.ai.service.AiChatService;
import com.codesight.common.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * AI 文章智能问答控制层
 */
@Tag(name = "AI 文章智能问答")
@RestController
@RequestMapping("/api/v1/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    @RateLimit(maxRequests = 10, windowSeconds = 60)
    @Operation(summary = "文章问答")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<OpenAiApi.ChatCompletionChunk> streamChat(@Valid @RequestBody AiChatRequest request) {
        return aiChatService.streamChat(request);
    }

    @RateLimit(maxRequests = 20, windowSeconds = 60)
    @Operation(summary = "智能技术追问推荐")
    @PostMapping("/suggest-questions")
    public SuggestQuestionsResponse suggestQuestions(@Valid @RequestBody SuggestQuestionsRequest request) {
        return aiChatService.suggestQuestions(request);
    }
}
