package com.codesight.ai.service;

import com.codesight.ai.api.dto.AiChatRequest;
import com.codesight.ai.api.dto.SuggestQuestionsRequest;
import com.codesight.ai.api.dto.SuggestQuestionsResponse;
import org.springframework.ai.openai.api.OpenAiApi;
import com.codesight.article.api.dto.response.ArticleDetailResponse;
import com.codesight.article.service.ArticleService;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
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
    private ArticleService articleService;

    private AiChatService aiChatService;

    @BeforeEach
    void setUp() {
        ChatClient chatClient = ChatClient.create(chatModel);
        aiChatService = new AiChatService(chatClient, articleService);
    }

    @Test
    @DisplayName("测试单篇伴读模式流式问答：从 ArticleService 提取正文切片并流式输出")
    void testStreamChat_CurrentArticleCopilot() {
        Long articleId = 888L;
        ArticleDetailResponse articleDetail = ArticleDetailResponse.builder()
                .id(articleId)
                .title("深入理解 Netty 零拷贝")
                .summary("Netty 通过 ByteBuf 切片、CompositeByteBuf 与 FileRegion 实现高效传输")
                .contentMd("# 深入理解 Netty 零拷贝\nNetty 是一个高性能 NIO 框架...")
                .build();
        when(articleService.getDetail(articleId, null)).thenReturn(articleDetail);

        Generation gen = new Generation(new AssistantMessage("Netty 零拷贝主要依靠 FileRegion 与 ByteBuf 复合缓冲区实现。"));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(gen))));

        AiChatRequest req = AiChatRequest.builder()
                .articleId(articleId)
                .question("总结这篇文章的核心论点")
                .build();

        List<OpenAiApi.ChatCompletionChunk> chunks = aiChatService.streamChat(req).collectList().block();

        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("Netty 零拷贝主要依靠 FileRegion 与 ByteBuf 复合缓冲区实现。", chunks.getFirst().choices().getFirst().delta().content());
        verify(articleService).getDetail(articleId, null);
    }

    @Test
    @DisplayName("测试伴读文章不存在时：抛出 ARTICLE_NOT_FOUND 业务异常")
    void testStreamChat_ArticleNotFound_ShouldThrowException() {
        Long articleId = 999L;
        when(articleService.getDetail(articleId, null)).thenReturn(null);

        AiChatRequest req = AiChatRequest.builder()
                .articleId(articleId)
                .question("什么是布隆过滤器？")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () -> aiChatService.streamChat(req));
        assertEquals(ErrorCode.ARTICLE_NOT_FOUND, ex.getErrorCode());
        verify(articleService).getDetail(articleId, null);
    }

    @Test
    @DisplayName("测试大模型调用异常时：抛出 AI_SERVICE_ERROR 业务异常")
    void testStreamChat_AiError_ShouldThrowException() {
        Long articleId = 123L;
        ArticleDetailResponse articleDetail = ArticleDetailResponse.builder()
                .id(articleId)
                .title("测试文章")
                .contentMd("文章内容")
                .build();
        when(articleService.getDetail(articleId, null)).thenReturn(articleDetail);
        when(chatModel.stream(any(Prompt.class))).thenThrow(new RuntimeException("OpenAI API 500 error"));

        AiChatRequest req = AiChatRequest.builder()
                .articleId(articleId)
                .question("测试提问")
                .build();

        Flux<OpenAiApi.ChatCompletionChunk> stream = aiChatService.streamChat(req);
        BusinessException ex = assertThrows(BusinessException.class, () -> stream.collectList().block());
        assertEquals(ErrorCode.AI_SERVICE_ERROR, ex.getErrorCode());
    }

    @Test
    @DisplayName("测试多轮上下文流式问答：携带 chatHistory 时正确组装并返回流式回答")
    void testStreamChat_MultiTurnConversation() {
        Long articleId = 555L;
        ArticleDetailResponse articleDetail = ArticleDetailResponse.builder()
                .id(articleId)
                .title("Spring WebFlux 源码解析")
                .summary("基于 Project Reactor 响应式编程")
                .contentMd("WebFlux 是 Spring 5 引入的响应式框架...")
                .build();
        when(articleService.getDetail(articleId, null)).thenReturn(articleDetail);

        AiChatRequest.ChatMessage m1 = AiChatRequest.ChatMessage.builder()
                .role("user")
                .content("什么是响应式编程？")
                .build();
        AiChatRequest.ChatMessage m2 = AiChatRequest.ChatMessage.builder()
                .role("assistant")
                .content("响应式编程是一种面向数据流和变化传播的声明式编程范式。")
                .build();

        AiChatRequest req = AiChatRequest.builder()
                .articleId(articleId)
                .question("那它有什么缺点呢？")
                .chatHistory(List.of(m1, m2))
                .build();

        Generation gen = new Generation(new AssistantMessage("响应式编程的缺点包括：调试排查难、学习曲线陡峭。"));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(gen))));

        List<OpenAiApi.ChatCompletionChunk> chunks = aiChatService.streamChat(req).collectList().block();

        assertNotNull(chunks);
        assertEquals("响应式编程的缺点包括：调试排查难、学习曲线陡峭。", chunks.getFirst().choices().getFirst().delta().content());
    }

    @Test
    @DisplayName("测试智能追问推荐：成功提炼并解析 3 个推荐问题")
    void testSuggestQuestions() {
        Long articleId = 101L;
        when(articleService.getDetail(articleId, null)).thenReturn(ArticleDetailResponse.builder()
                .id(articleId)
                .title("Spring Boot 指南")
                .build());

        SuggestQuestionsRequest req = SuggestQuestionsRequest.builder()
                .articleId(articleId)
                .chatHistory(List.of(
                        AiChatRequest.ChatMessage.builder().role("user").content("解释一下什么是 Spring Boot").build(),
                        AiChatRequest.ChatMessage.builder().role("assistant").content("Spring Boot 是简化 Spring 开发的脚手架框架。").build()
                ))
                .build();

        String llmOutput = """
                1. 自动配置原理解析
                2. 常用核心注解
                3. 与 Spring MVC 区别
                """;
        Generation gen = new Generation(new AssistantMessage(llmOutput));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(gen)));

        SuggestQuestionsResponse resp = aiChatService.suggestQuestions(req);

        assertNotNull(resp);
        assertEquals(3, resp.queries().size());
        assertEquals("自动配置原理解析", resp.queries().get(0).value());
        assertEquals("常用核心注解", resp.queries().get(1).value());
        assertEquals("与 Spring MVC 区别", resp.queries().get(2).value());
    }

    @Test
    @DisplayName("测试智能追问推荐：结合文章背景与多轮历史生成追问")
    void testSuggestQuestions_WithChatHistoryAndArticle() {
        Long articleId = 777L;
        ArticleDetailResponse articleDetail = ArticleDetailResponse.builder()
                .id(articleId)
                .title("Spring 源码剖析")
                .summary("深入讲解 IoC 容器与 AOP 切面机制")
                .tags(List.of(new com.codesight.article.api.dto.response.TagResponse(1L, "Spring"), new com.codesight.article.api.dto.response.TagResponse(2L, "IoC")))
                .build();
        when(articleService.getDetail(articleId, null)).thenReturn(articleDetail);

        AiChatRequest.ChatMessage m1 = AiChatRequest.ChatMessage.builder().role("user").content("什么是 IoC？").build();
        AiChatRequest.ChatMessage m2 = AiChatRequest.ChatMessage.builder().role("assistant").content("IoC 即控制反转，将对象的创建交给容器管理。").build();
        AiChatRequest.ChatMessage m3 = AiChatRequest.ChatMessage.builder().role("user").content("你是什么模型").build();
        AiChatRequest.ChatMessage m4 = AiChatRequest.ChatMessage.builder().role("assistant").content("我是编程助手。").build();

        SuggestQuestionsRequest req = SuggestQuestionsRequest.builder()
                .articleId(articleId)
                .chatHistory(List.of(m1, m2, m3, m4))
                .build();

        String llmOutput = """
                IoC还有啥例子
                AOP使用场景有啥
                Spring有啥缺点
                """;
        Generation gen = new Generation(new AssistantMessage(llmOutput));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(gen)));

        SuggestQuestionsResponse resp = aiChatService.suggestQuestions(req);

        assertNotNull(resp);
        assertEquals(3, resp.queries().size());
        assertEquals("IoC还有啥例子", resp.queries().get(0).value());
        assertEquals("AOP使用场景有啥", resp.queries().get(1).value());
        assertEquals("Spring有啥缺点", resp.queries().get(2).value());
        verify(articleService).getDetail(articleId, null);
    }

    @Test
    @DisplayName("测试智能追问触发安全熔断输出为空时：取消推荐并返回空列表")
    void testSuggestQuestions_WhenSafetyTriggered_ShouldReturnEmptyList() {
        Long articleId = 102L;
        when(articleService.getDetail(articleId, null)).thenReturn(ArticleDetailResponse.builder().id(articleId).title("测试文章").build());

        SuggestQuestionsRequest req = SuggestQuestionsRequest.builder()
                .articleId(articleId)
                .chatHistory(List.of(
                        AiChatRequest.ChatMessage.builder().role("user").content("一些不当言论").build(),
                        AiChatRequest.ChatMessage.builder().role("assistant").content("请使用文明用语进行交流...").build()
                ))
                .build();
        Generation gen = new Generation(new AssistantMessage(""));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(gen)));

        SuggestQuestionsResponse resp = aiChatService.suggestQuestions(req);

        assertNotNull(resp);
        assertNotNull(resp.queries());
        assertTrue(resp.queries().isEmpty());
    }

    @Test
    @DisplayName("测试智能追问文章不存在时：抛出 ARTICLE_NOT_FOUND 业务异常")
    void testSuggestQuestions_ArticleNotFound_ShouldThrowException() {
        Long articleId = 999L;
        when(articleService.getDetail(articleId, null)).thenReturn(null);

        SuggestQuestionsRequest req = SuggestQuestionsRequest.builder()
                .articleId(articleId)
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () -> aiChatService.suggestQuestions(req));
        assertEquals(ErrorCode.ARTICLE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("测试智能追问大模型异常时：抛出 AI_SERVICE_ERROR 业务异常")
    void testSuggestQuestions_AiError_ShouldThrowException() {
        Long articleId = 103L;
        when(articleService.getDetail(articleId, null)).thenReturn(ArticleDetailResponse.builder().id(articleId).title("测试文章").build());

        SuggestQuestionsRequest req = SuggestQuestionsRequest.builder()
                .articleId(articleId)
                .chatHistory(List.of(AiChatRequest.ChatMessage.builder().role("user").content("测试").build()))
                .build();
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM Timeout"));

        BusinessException ex = assertThrows(BusinessException.class, () -> aiChatService.suggestQuestions(req));
        assertEquals(ErrorCode.AI_SERVICE_ERROR, ex.getErrorCode());
    }
}
