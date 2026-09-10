package com.codesight.search.event;

import com.codesight.article.event.ArticleSyncEvent;
import com.codesight.search.index.SearchIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArticleSearchKafkaConsumerTest {

    @Mock
    private SearchIndexService searchIndexService;

    @Mock
    private Acknowledgment ack;

    @InjectMocks
    private ArticleSearchKafkaConsumer consumer;

    @Test
    void shouldCallUpsertAndAckWhenActionIsUpsert() {
        ArticleSyncEvent event = new ArticleSyncEvent(1001L, ArticleSyncEvent.Action.UPSERT);

        consumer.onMessage(event, ack);

        verify(searchIndexService, times(1)).upsertArticle(1001L);
        verify(searchIndexService, never()).deleteArticle(any());
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void shouldCallDeleteAndAckWhenActionIsDelete() {
        ArticleSyncEvent event = new ArticleSyncEvent(1001L, ArticleSyncEvent.Action.DELETE);

        consumer.onMessage(event, ack);

        verify(searchIndexService, times(1)).deleteArticle(1001L);
        verify(searchIndexService, never()).upsertArticle(any());
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void shouldHandleNullEventGracefully() {
        consumer.onMessage(null, ack);

        verifyNoInteractions(searchIndexService);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void shouldHandleNullArticleIdGracefully() {
        ArticleSyncEvent event = new ArticleSyncEvent(null, ArticleSyncEvent.Action.UPSERT);

        consumer.onMessage(event, ack);

        verifyNoInteractions(searchIndexService);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void shouldHandleExceptionGracefullyAndAck() {
        ArticleSyncEvent event = new ArticleSyncEvent(1001L, ArticleSyncEvent.Action.UPSERT);
        doThrow(new RuntimeException("ES unavailable")).when(searchIndexService).upsertArticle(1001L);

        consumer.onMessage(event, ack);

        verify(searchIndexService, times(1)).upsertArticle(1001L);
        verify(ack, times(1)).acknowledge();
    }
}
