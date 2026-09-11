package com.codesight.ai.service;

import com.codesight.ai.api.dto.AiChatRequest;
import com.codesight.ai.api.dto.SuggestQuestionsRequest;
import com.codesight.ai.api.dto.SuggestQuestionsResponse;
import org.springframework.ai.openai.api.OpenAiApi;
import com.codesight.article.api.dto.response.ArticleDetailResponse;
import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.service.ArticleService;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 文章伴读与智能追问服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private static final int MAX_ARTICLE_CONTENT_LENGTH = 30000;

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是技术社区伴读助手与资深工程师。
            围绕用户问题，结合【参考文章】和【近期对话】给出客观、严谨的技术解答。

            回答规则：
            1. 针对当前文章提问，优先基于【参考文章】客观回答。
            2. 若【参考文章】没有依据或信息不足，再基于通用知识补充。
            3. 通用技术问题结合专业知识回答，必要时给出对比、适用场景、取舍和踩坑点。
            4. 表达自然流畅、直奔主题，根据内容复杂度组织结构；代码示例使用规范 Markdown 代码块并标注语言。
            5. 保持技术中立，不吹捧特定技术；不确定时说明假设和边界。
            6. 遇到非技术问题，避免回答并引导用户聚焦技术。
            """;

    private static final String SUGGEST_QUESTIONS_PROMPT = """
            你是一个技术会话的智能追问推荐引擎。
            请根据【文章背景】与【近期多轮对话脉络】，生成 3 个读者最可能顺着当前语境继续点击探索的简短追问短语。

            推荐规则：
            1. 意图跟随优先：若存在【近期对话】，优先围绕读者最新讨论的具体技术点向下深挖，其次基于全文大纲推荐。
            2. 去重：不要重复用户已问过、助手已详细回答过或近期已推荐过的问题。
            3. 多样性：3 条尽量覆盖不同角度，如原理、场景、对比、实践、踩坑。
            4. 具体可点击：每条尽量 10 字以内，简洁且专业。
            5. 严格只输出 3 行，每行一个短语；不要序号、引号、Markdown、空行或额外解释。
            """;

    private final ChatClient chatClient;
    private final ArticleService articleService;

    /**
     * 流式问答
     */
    public Flux<OpenAiApi.ChatCompletionChunk> streamChat(AiChatRequest request) {
        Prompt prompt = buildPrompt(
                DEFAULT_SYSTEM_PROMPT,
                request.articleId(),
                request.chatHistory(),
                "【用户提问】\n" + request.question()
        );
        return chatClient.prompt(prompt)
                .stream()
                .chatResponse()
                .map(response -> {
                    var generation = response.getResult();
                    String content = generation.getOutput().getText();
                    String id = response.getMetadata().getId();
                    String model = response.getMetadata().getModel();

                    var message = new OpenAiApi.ChatCompletionMessage(content, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);
                    var choice = new OpenAiApi.ChatCompletionChunk.ChunkChoice(null, 0, message, null);
                    return new OpenAiApi.ChatCompletionChunk(
                            id,
                            List.of(choice),
                            System.currentTimeMillis() / 1000,
                            model,
                            null,
                            null,
                            "chat.completion.chunk",
                            null
                    );
                })
                .onErrorMap(e -> {
                    log.error("AI 流式问答异常: {}", e.getMessage(), e);
                    return new BusinessException(ErrorCode.AI_SERVICE_ERROR);
                });
    }

    /**
     * 智能追问推荐
     */
    public SuggestQuestionsResponse suggestQuestions(SuggestQuestionsRequest request) {
        Prompt prompt = buildPrompt(
                SUGGEST_QUESTIONS_PROMPT,
                request.articleId(),
                request.chatHistory(),
                "请基于上述背景与近期对话，严格按规则推荐 3 个技术追问短语："
        );

        String content;
        try {
            content = chatClient.prompt(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("生成智能追问推荐异常: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR);
        }

        if (content == null || content.isBlank()) {
            return SuggestQuestionsResponse.of(Collections.emptyList());
        }

        List<String> questions = new ArrayList<>();
        for (String line : content.split("\\r?\\n")) {
            String cleaned = line.replaceFirst("^[0-9]+[.\\s、]+", "").trim();
            if (!cleaned.isBlank()) {
                questions.add(cleaned);
            }
            if (questions.size() >= 3) {
                break;
            }
        }
        return SuggestQuestionsResponse.of(questions);
    }

    /**
     * 构造提示词
     */
    private Prompt buildPrompt(String systemPrompt, Long articleId, List<AiChatRequest.ChatMessage> chatHistory, String finalInstruction) {
        ArticleDetailResponse article = articleService.getDetail(articleId, null);
        if (article == null) {
            throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        // 注入历史多轮对话
        if (chatHistory != null) {
            for (AiChatRequest.ChatMessage msg : chatHistory) {
                if (msg != null && msg.content() != null && !msg.content().isBlank()) {
                    if ("assistant".equalsIgnoreCase(msg.role())) {
                        messages.add(new AssistantMessage(msg.content()));
                    } else {
                        messages.add(new UserMessage(msg.content()));
                    }
                }
            }
        }

        // 注入文章上下文
        StringBuilder userPrompt = new StringBuilder("【用户当前正在浏览的文章】\n");
        userPrompt.append("标题：").append(article.getTitle()).append("\n");
        if (article.getTags() != null && !article.getTags().isEmpty()) {
            String tags = article.getTags().stream()
                    .map(TagResponse::name)
                    .collect(Collectors.joining("、"));
            userPrompt.append("标签：").append(tags).append("\n");
        }
        if (article.getSummary() != null && !article.getSummary().isBlank()) {
            userPrompt.append("摘要：").append(article.getSummary()).append("\n");
        }
        String contentMd = article.getContentMd();
        if (contentMd != null && !contentMd.isBlank()) {
            String snippet = contentMd.length() > MAX_ARTICLE_CONTENT_LENGTH
                    ? contentMd.substring(0, MAX_ARTICLE_CONTENT_LENGTH) + "\n\n...(正文后续篇幅已省略)..."
                    : contentMd;
            userPrompt.append("正文切片：\n").append(snippet).append("\n\n");
        }

        userPrompt.append("请结合上述资料回答：\n").append(finalInstruction);
        messages.add(new UserMessage(userPrompt.toString()));
        return new Prompt(messages);
    }
}