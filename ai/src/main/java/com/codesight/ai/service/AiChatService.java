package com.codesight.ai.service;

import com.codesight.ai.api.dto.AiChatRequest;
import com.codesight.ai.api.dto.RagChatRequest;
import com.codesight.ai.api.dto.SuggestQuestionsRequest;
import com.codesight.ai.api.dto.SuggestQuestionsResponse;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.search.index.ArticleSearchDoc;
import com.codesight.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private final SearchService searchService;

    /**
     * 流式问答
     */
    public Flux<OpenAiApi.ChatCompletionChunk> streamChat(AiChatRequest request) {
        Prompt prompt = buildPrompt(
                DEFAULT_SYSTEM_PROMPT,
                request.chatHistory(),
                request.articleContext(),
                "【用户提问】\n" + request.question()
        );
        return executeStreamChat(prompt, "AI 单篇伴读流式问答异常");
    }

    /**
     * 智能追问推荐
     */
    public SuggestQuestionsResponse suggestQuestions(SuggestQuestionsRequest request) {
        Prompt prompt = buildPrompt(
                SUGGEST_QUESTIONS_PROMPT,
                request.chatHistory(),
                request.articleContext(),
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
     * 构造提示词（无状态上下文直传，零查库）
     */
    private Prompt buildPrompt(
            String systemPrompt,
            List<AiChatRequest.ChatMessage> chatHistory,
            AiChatRequest.ArticleContext context,
            String finalInstruction) {

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

        // 注入文章上下文（前端直传上下文）
        StringBuilder userPrompt = new StringBuilder("【用户当前正在浏览的文章】\n");
        if (context != null) {
            if (context.title() != null && !context.title().isBlank()) {
                userPrompt.append("标题：").append(context.title()).append("\n");
            }
            if (context.tags() != null && !context.tags().isEmpty()) {
                userPrompt.append("标签：").append(String.join("、", context.tags())).append("\n");
            }
            if (context.summary() != null && !context.summary().isBlank()) {
                userPrompt.append("摘要：").append(context.summary()).append("\n");
            }
            String contentMd = context.content();
            if (contentMd != null && !contentMd.isBlank()) {
                String snippet = contentMd.length() > MAX_ARTICLE_CONTENT_LENGTH
                        ? contentMd.substring(0, MAX_ARTICLE_CONTENT_LENGTH) + "\n\n...(正文后续篇幅已省略)..."
                        : contentMd;
                userPrompt.append("正文切片：\n").append(snippet).append("\n\n");
            }
        }

        userPrompt.append("请结合上述资料回答：\n").append(finalInstruction);
        messages.add(new UserMessage(userPrompt.toString()));
        return new Prompt(messages);
    }

    /**
     * 全站技术知识库流式问答（RAG）
     */
    public Flux<OpenAiApi.ChatCompletionChunk> streamRagChat(RagChatRequest request) {
        String question = request.question().trim();
        List<ArticleSearchDoc> relevantDocs = (searchService != null)
                ? searchService.searchRelevantArticles(question, 3)
                : Collections.emptyList();

        Prompt prompt = buildRagPrompt(question, relevantDocs);
        return executeStreamChat(prompt, "全站知识库 RAG 流式问答异常");
    }

    /**
     * 统一驱动 ChatClient 进行流式对话并转换为 SSE Chunk
     */
    private Flux<OpenAiApi.ChatCompletionChunk> executeStreamChat(Prompt prompt, String errorLogMsg) {
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
                    log.error("{}: {}", errorLogMsg, e.getMessage(), e);
                    return new BusinessException(ErrorCode.AI_SERVICE_ERROR);
                });
    }

    /**
     * 动态装配 RAG 提示词
     */
    private Prompt buildRagPrompt(String question, List<ArticleSearchDoc> docs) {
        StringBuilder systemContent = new StringBuilder("""
                你是 Codesight 技术社区的全站知识库专家。请结合站内检索出的真实技术文章，客观、严谨地回答用户的问题。

                回答要求：
                1. 优先基于【站内参考文章】组织解答。若引用了某篇参考文章的具体结论或设计，请在对应解答处标明引用，如：参考自《文章标题》。
                2. 若【站内参考文章】未涵盖问题所需内容，请基于通用计算机技术知识客观补充，并予以说明。
                3. 代码示例必须规范，使用对应语言的代码块。
                """);

        if (docs == null || docs.isEmpty()) {
            systemContent.append("\n【站内检索结果】：站内暂无直接收录该主题的文章，请基于专业技术经验提供权威解答，并说明站内暂未检索到直接文献。\n");
        } else {
            systemContent.append("\n【站内参考文章列表】：\n");
            for (int i = 0; i < docs.size(); i++) {
                ArticleSearchDoc doc = docs.get(i);
                systemContent.append(String.format("### [参考文章 %d] 《%s》（文章ID: %s）\n", i + 1, doc.title(), doc.articleId()));
                if (doc.summary() != null && !doc.summary().isBlank()) {
                    systemContent.append("摘要：").append(doc.summary()).append("\n");
                }
                if (doc.body() != null && !doc.body().isBlank()) {
                    String bodySnippet = doc.body().replaceAll("```[\\s\\S]*?```", " ").replaceAll("\\s+", " ").trim();
                    if (bodySnippet.length() > 800) {
                        bodySnippet = bodySnippet.substring(0, 800) + "...";
                    }
                    systemContent.append("核心内容节选：").append(bodySnippet).append("\n");
                }
                systemContent.append("\n");
            }
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemContent.toString()));
        messages.add(new UserMessage("【用户提问】\n" + question));
        return new Prompt(messages);
    }
}
