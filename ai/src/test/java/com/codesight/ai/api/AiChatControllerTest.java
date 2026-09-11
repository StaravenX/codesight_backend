package com.codesight.ai.api;

import com.codesight.ai.api.dto.AiChatRequest;
import com.codesight.ai.api.dto.SuggestQuestionsRequest;
import com.codesight.ai.api.dto.SuggestQuestionsResponse;
import com.codesight.ai.service.AiChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatControllerTest {

    @Mock
    private AiChatService aiChatService;

    @InjectMocks
    private AiChatController aiChatController;

    @Test
    @DisplayName("测试流式问答接口：POST 传递多轮上下文并返回 Flux")
    void testStreamChat() {
        AiChatRequest request = AiChatRequest.builder()
                .question("流式问题")
                .articleId(101L)
                .build();

        OpenAiApi.ChatCompletionMessage m1 = new OpenAiApi.ChatCompletionMessage("流式", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);
        OpenAiApi.ChatCompletionChunk.ChunkChoice c1 = new OpenAiApi.ChatCompletionChunk.ChunkChoice(null, 0, m1, null);
        OpenAiApi.ChatCompletionChunk chunk1 = new OpenAiApi.ChatCompletionChunk("1", List.of(c1), 1L, "model", null, null, "chat.completion.chunk", null);

        OpenAiApi.ChatCompletionMessage m2 = new OpenAiApi.ChatCompletionMessage("数据", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);
        OpenAiApi.ChatCompletionChunk.ChunkChoice c2 = new OpenAiApi.ChatCompletionChunk.ChunkChoice(null, 0, m2, null);
        OpenAiApi.ChatCompletionChunk chunk2 = new OpenAiApi.ChatCompletionChunk("1", List.of(c2), 1L, "model", null, null, "chat.completion.chunk", null);

        when(aiChatService.streamChat(request)).thenReturn(Flux.just(chunk1, chunk2));

        Flux<OpenAiApi.ChatCompletionChunk> stream = aiChatController.streamChat(request);

        List<OpenAiApi.ChatCompletionChunk> collected = stream.collectList().block();
        assertNotNull(collected);
        assertEquals(2, collected.size());
        assertEquals("流式", collected.get(0).choices().getFirst().delta().content());
        assertEquals("数据", collected.get(1).choices().getFirst().delta().content());
    }

    @Test
    @DisplayName("测试智能追问推荐接口：正确委托并返回推荐词条")
    void testSuggestQuestions() {
        SuggestQuestionsRequest request = SuggestQuestionsRequest.builder()
                .articleId(101L)
                .chatHistory(List.of(
                        AiChatRequest.ChatMessage.builder().role("user").content("什么是 Spring Boot").build()
                ))
                .build();
        SuggestQuestionsResponse mockResp = SuggestQuestionsResponse.of(List.of("自动配置", "核心注解", "Starter原理"));
        when(aiChatService.suggestQuestions(request)).thenReturn(mockResp);

        SuggestQuestionsResponse response = aiChatController.suggestQuestions(request);

        assertNotNull(response);
        assertEquals(3, response.queries().size());
        assertEquals("自动配置", response.queries().getFirst().value());
        verify(aiChatService).suggestQuestions(request);
    }
}
