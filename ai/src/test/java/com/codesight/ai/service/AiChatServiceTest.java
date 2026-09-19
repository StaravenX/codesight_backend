package com.codesight.ai.service;

import com.codesight.ai.api.dto.AiChatRequest;
import com.codesight.ai.api.dto.RagChatRequest;
import com.codesight.ai.api.dto.SuggestQuestionsRequest;
import com.codesight.ai.api.dto.SuggestQuestionsResponse;
import com.codesight.search.index.ArticleSearchDoc;
import com.codesight.search.service.SearchService;
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

    @Mock
    private SearchService searchService;

    private AiChatService aiChatService;

    @BeforeEach
    void setUp() {
        ChatClient chatClient = ChatClient.create(chatModel);
        aiChatService = new AiChatService(chatClient, searchService);
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
                        AiChatRequest.ChatMessage.builder().role("user").content("什么是零拷贝？").build(),
                        AiChatRequest.ChatMessage.builder().role("assistant").content("零拷贝指减少 CPU 拷贝与系统态切换...").build()
                ))
                .articleContext(context)
                .build();

        Flux<OpenAiApi.ChatCompletionChunk> chunkFlux = aiChatService.streamChat(request);
        assertNotNull(chunkFlux);

        List<OpenAiApi.ChatCompletionChunk> chunks = chunkFlux.collectList().block();
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertEquals("Netty 零拷贝主要依靠 FileRegion 与 ByteBuf 复合缓冲区实现。",
                chunks.getFirst().choices().getFirst().delta().content());
    }

    @Test
    @DisplayName("测试智能追问推荐生成：成功解析三行纯文本并返回 List")
    void testSuggestQuestions_Success() {
        String llmOutput = """
                Netty 零拷贝原理
                FileRegion 底层实现
                零拷贝与 mmap 对比
                """;

        Generation gen = new Generation(new AssistantMessage(llmOutput));
        ChatResponse chatResponse = new ChatResponse(List.of(gen));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        SuggestQuestionsRequest request = SuggestQuestionsRequest.builder()
                .chatHistory(List.of(
                        AiChatRequest.ChatMessage.builder().role("user").content("什么是零拷贝？").build(),
                        AiChatRequest.ChatMessage.builder().role("assistant").content("零拷贝就是直接内存映射...").build()
                ))
                .articleContext(AiChatRequest.ArticleContext.builder()
                        .title("Netty 进阶")
                        .content("长文正文...")
                        .build())
                .build();

        SuggestQuestionsResponse response = aiChatService.suggestQuestions(request);

        assertNotNull(response);
        assertEquals(3, response.queries().size());
        assertEquals("Netty 零拷贝原理", response.queries().get(0).value());
        assertEquals("FileRegion 底层实现", response.queries().get(1).value());
        assertEquals("零拷贝与 mmap 对比", response.queries().get(2).value());
    }

    @Test
    @DisplayName("测试智能追问推荐生成：LLM 异常时抛出业务异常")
    void testSuggestQuestions_Exception() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM Timeout"));

        SuggestQuestionsRequest request = SuggestQuestionsRequest.builder()
                .build();

        assertThrows(com.codesight.common.exception.BusinessException.class,
                () -> aiChatService.suggestQuestions(request));
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

    @Test
    @DisplayName("测试全站知识库 RAG 流式问答（命中参考文章）")
    void testStreamRagChat_SuccessWithArticles() {
        ArticleSearchDoc doc = ArticleSearchDoc.builder()
                .articleId(999L)
                .title("Codesight 计数自愈机制")
                .summary("基于 SDS 实时聚合与增量补偿")
                .body("详细阐述 16B SDS 结构...")
                .build();
        when(searchService.searchRelevantArticles("Codesight 自愈", 3))
                .thenReturn(List.of(doc));

        Generation gen = new Generation(new AssistantMessage("参考自《Codesight 计数自愈机制》，核心在于 SDS 实时聚合。"));
        ChatResponse chatResponse = new ChatResponse(List.of(gen));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse));

        RagChatRequest request = new RagChatRequest("Codesight 自愈");
        List<OpenAiApi.ChatCompletionChunk> chunks = aiChatService.streamRagChat(request).collectList().block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertEquals("参考自《Codesight 计数自愈机制》，核心在于 SDS 实时聚合。",
                chunks.getFirst().choices().getFirst().delta().content());
    }

    @Test
    @DisplayName("测试全站知识库 RAG 流式问答（未命中文章，优雅降级）")
    void testStreamRagChat_FallbackWhenEmpty() {
        when(searchService.searchRelevantArticles("未知冷门技术", 3))
                .thenReturn(List.of());

        Generation gen = new Generation(new AssistantMessage("站内暂无收录，基于通用经验建议如下：..."));
        ChatResponse chatResponse = new ChatResponse(List.of(gen));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse));

        RagChatRequest request = new RagChatRequest("未知冷门技术");
        List<OpenAiApi.ChatCompletionChunk> chunks = aiChatService.streamRagChat(request).collectList().block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertEquals("站内暂无收录，基于通用经验建议如下：...",
                chunks.getFirst().choices().getFirst().delta().content());
    }
}
