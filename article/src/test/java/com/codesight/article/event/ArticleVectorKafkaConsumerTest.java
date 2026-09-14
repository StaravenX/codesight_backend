package com.codesight.article.event;

import com.codesight.ai.service.ArticleVectorService;
import com.codesight.article.mapper.ArticleMapper;
import com.codesight.article.model.entity.Article;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleVectorKafkaConsumerTest {

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleVectorService articleVectorService;

    @Mock
    private ArticleEventProducer articleEventProducer;

    @Mock
    private Acknowledgment ack;

    @InjectMocks
    private ArticleVectorKafkaConsumer consumer;

    @Test
    void shouldGenerateAndSaveVectorWhenArticleIsPublishedAndPublic() {
        Long articleId = 1001L;
        Article article = Article.builder()
                .id(articleId)
                .title("Java 21 虚拟线程深度解析")
                .summary("核心摘要")
                .contentMd("正文内容...")
                .status(ArticleStatus.PUBLISHED)
                .visible(ArticleVisible.PUBLIC)
                .build();

        when(articleMapper.selectById(articleId)).thenReturn(article);
        when(articleVectorService.generateAndSaveVector(
                articleId,
                "Java 21 虚拟线程深度解析",
                "核心摘要",
                "正文内容..."
        )).thenReturn(new float[]{0.1f, 0.2f});

        ArticleSyncEvent event = new ArticleSyncEvent(articleId, ArticleSyncEvent.Action.UPSERT);
        consumer.onMessage(event, ack);

        verify(articleVectorService, times(1)).generateAndSaveVector(
                articleId,
                "Java 21 虚拟线程深度解析",
                "核心摘要",
                "正文内容..."
        );
        verify(articleEventProducer, times(1)).sendVectorSyncEvent(articleId, ArticleSyncEvent.Action.UPSERT);
        verify(articleVectorService, never()).deleteArticleVector(any());
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void shouldDeleteVectorWhenArticleIsDraftOrPrivate() {
        Long articleId = 1002L;
        Article article = Article.builder()
                .id(articleId)
                .title("私密草稿")
                .summary("草稿摘要")
                .contentMd("正文")
                .status(ArticleStatus.DRAFT)
                .visible(ArticleVisible.PRIVATE)
                .build();

        when(articleMapper.selectById(articleId)).thenReturn(article);

        ArticleSyncEvent event = new ArticleSyncEvent(articleId, ArticleSyncEvent.Action.UPSERT);
        consumer.onMessage(event, ack);

        verify(articleVectorService, times(1)).deleteArticleVector(articleId);
        verify(articleEventProducer, times(1)).sendVectorSyncEvent(articleId, ArticleSyncEvent.Action.DELETE);
        verify(articleVectorService, never()).generateAndSaveVector(any(), any(), any(), any());
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void shouldDeleteVectorWhenArticleNotFound() {
        Long articleId = 1003L;
        when(articleMapper.selectById(articleId)).thenReturn(null);

        ArticleSyncEvent event = new ArticleSyncEvent(articleId, ArticleSyncEvent.Action.UPSERT);
        consumer.onMessage(event, ack);

        verify(articleVectorService, times(1)).deleteArticleVector(articleId);
        verify(articleEventProducer, times(1)).sendVectorSyncEvent(articleId, ArticleSyncEvent.Action.DELETE);
        verify(articleVectorService, never()).generateAndSaveVector(any(), any(), any(), any());
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void shouldCallDeleteVectorWhenActionIsDelete() {
        Long articleId = 1004L;
        ArticleSyncEvent event = new ArticleSyncEvent(articleId, ArticleSyncEvent.Action.DELETE);

        consumer.onMessage(event, ack);

        verify(articleVectorService, times(1)).deleteArticleVector(articleId);
        verify(articleEventProducer, times(1)).sendVectorSyncEvent(articleId, ArticleSyncEvent.Action.DELETE);
        verifyNoInteractions(articleMapper);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void shouldHandleNullEventGracefully() {
        consumer.onMessage(null, ack);

        verifyNoInteractions(articleMapper);
        verifyNoInteractions(articleVectorService);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void shouldHandleNullArticleIdGracefully() {
        ArticleSyncEvent event = new ArticleSyncEvent(null, ArticleSyncEvent.Action.UPSERT);
        consumer.onMessage(event, ack);

        verifyNoInteractions(articleMapper);
        verifyNoInteractions(articleVectorService);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void shouldHandleExceptionGracefullyAndAck() {
        Long articleId = 1005L;
        when(articleMapper.selectById(articleId)).thenThrow(new RuntimeException("DB Connection Timeout"));

        ArticleSyncEvent event = new ArticleSyncEvent(articleId, ArticleSyncEvent.Action.UPSERT);
        consumer.onMessage(event, ack);

        verify(ack, times(1)).acknowledge();
    }
}
