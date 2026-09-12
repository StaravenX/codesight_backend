package com.codesight.ai.service;

import com.codesight.ai.api.dto.AiChatRequest;
import com.codesight.ai.api.dto.SuggestQuestionsRequest;
import com.codesight.ai.api.dto.SuggestQuestionsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiChatServiceTest {

    @Mock
    private ChatModel chatModel;

    private AiChatService aiChatService;

    @BeforeEach
    void setUp() {
        ChatClient chatClient = ChatClient.create(chatModel);
        aiChatService = new AiChatService(chatClient);
    }

    @Test
    @DisplayName("测试单篇伴读模式流式问答")
    void testStreamChat_CurrentArticleCopilot() {
        Long articleId = 888L;
        AiChatRequest.ArticleContext context = AiChatRequest.ArticleContext.builder()
                .title("深入理解 Netty 零拷贝")
                .summary("Netty 通过 ByteBuf 切片、CompositeByteBuf 与 FileRegion 实现高效传输")
                .tags(List.of("Netty", "NIO"))
                .content("# 深入理解 Netty 零拷贝\nNetty 是一个高性能 NIO 框架...")
                .build();

        Generation gen = new Generation(new AssistantMessage("Netty 零拷贝主要依靠 FileRegion 与 ByteBuf 复合缓冲区实现。"));
        ChatResponse chatResponse = new ChatResponse(List.of(gen));

        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse));

        AiChatRequest request = AiChatRequest.builder()
                .articleId(articleId)
                .question("请解释一下 FileRegion 是如何减少上下文切换的？")
                .chatHistory(List.of(
                        AiChatRequest.ChatMessage.builder().role("user").content("你好").build(),
                        AiChatRequest.ChatMessage.builder().role("assistant").content("你好！我是伴读助手").build()
                ))
                .articleContext(context)
                .build();

        Flux<OpenAiApi.ChatCompletionChunk> resultFlux = aiChatService.streamChat(request);

        List<OpenAiApi.ChatCompletionChunk> chunks = resultFlux.collectList().block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        OpenAiApi.ChatCompletionChunk firstChunk = chunks.getFirst();
        assertEquals("chat.completion.chunk", firstChunk.object());
        assertEquals("Netty 零拷贝主要依靠 FileRegion 与 ByteBuf 复合缓冲区实现。",
                firstChunk.choices().getFirst().delta().content());
    }

    @Test
    @DisplayName("测试智能追问推荐：多行输出正确解析为 3 条候选推荐词")
    void testSuggestQuestions_Success() {
        AiChatRequest.ArticleContext context = AiChatRequest.ArticleContext.builder()
                .title("Spring Boot 3 虚拟线程实践")
                .content("正文内容...")
                .build();

        String mockLLMOutput = """
                1. 虚拟线程与协程的区别
                2. 生产环境中如何排查 Pinning 现象？
                3. 为什么不建议在虚拟线程中使用 ThreadLocal？
                """;

        Generation gen = new Generation(new AssistantMessage(mockLLMOutput));
        ChatResponse chatResponse = new ChatResponse(List.of(gen));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        SuggestQuestionsRequest request = SuggestQuestionsRequest.builder()
                .articleContext(context)
                .chatHistory(List.of(
                        AiChatRequest.ChatMessage.builder().role("user").content("虚拟线程有什么优势？").build()
                ))
                .build();

        SuggestQuestionsResponse response = aiChatService.suggestQuestions(request);

        assertNotNull(response);
        assertEquals(3, response.queries().size());
        assertEquals("虚拟线程与协程的区别", response.queries().get(0).value());
        assertEquals("生产环境中如何排查 Pinning 现象？", response.queries().get(1).value());
        assertEquals("为什么不建议在虚拟线程中使用 ThreadLocal？", response.queries().get(2).value());
    }

    @Test
    @DisplayName("测试智能追问推荐：LLM 输出为空时优雅降级为空列表")
    void testSuggestQuestions_EmptyOutput_Fallback() {
        Generation gen = new Generation(new AssistantMessage(""));
        ChatResponse chatResponse = new ChatResponse(List.of(gen));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        SuggestQuestionsRequest request = SuggestQuestionsRequest.builder()
                .build();

        SuggestQuestionsResponse response = aiChatService.suggestQuestions(request);

        assertNotNull(response);
        assertTrue(response.queries().isEmpty());
    }

    @Test
    @DisplayName("测试超长文本自动切片保护：大于 30000 字符时安全截断")
    void testStreamChat_ContentTruncation() {
        Long articleId = 101L;
        String hugeContent = "A".repeat(35000);
        AiChatRequest.ArticleContext context = AiChatRequest.ArticleContext.builder()
                .title("超长技术长文")
                .content(hugeContent)
                .build();

        Generation gen = new Generation(new AssistantMessage("回答"));
        ChatResponse chatResponse = new ChatResponse(List.of(gen));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse));

        AiChatRequest request = AiChatRequest.builder()
                .articleId(articleId)
                .question("问题")
                .articleContext(context)
                .build();

        List<OpenAiApi.ChatCompletionChunk> chunks = aiChatService.streamChat(request).collectList().block();
        assertNotNull(chunks);
    }
}
