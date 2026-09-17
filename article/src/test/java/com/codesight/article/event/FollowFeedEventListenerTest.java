package com.codesight.article.event;

import com.codesight.article.service.ArticleFeedService;
import com.codesight.relation.event.FollowEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowFeedEventListener 关注/取关 Feed 同步监听器测试")
class FollowFeedEventListenerTest {

    @Mock
    private ArticleFeedService articleFeedService;

    @InjectMocks
    private FollowFeedEventListener followFeedEventListener;

    @Test
    @DisplayName("收到 FOLLOW 事件：触发关注回填")
    void testOnFollowEvent_Follow() {
        FollowEvent event = new FollowEvent(101L, 202L, FollowEvent.FollowAction.FOLLOW);
        followFeedEventListener.onFollowEvent(event);

        verify(articleFeedService, timeout(2000)).backfillOnFollow(101L, 202L);
        verify(articleFeedService, never()).cleanupOnUnfollow(any(), any());
    }

    @Test
    @DisplayName("收到 UNFOLLOW 事件：触发取关清理")
    void testOnFollowEvent_Unfollow() {
        FollowEvent event = new FollowEvent(101L, 202L, FollowEvent.FollowAction.UNFOLLOW);
        followFeedEventListener.onFollowEvent(event);

        verify(articleFeedService, timeout(2000)).cleanupOnUnfollow(101L, 202L);
        verify(articleFeedService, never()).backfillOnFollow(any(), any());
    }

    @Test
    @DisplayName("空事件或不完整字段防御")
    void testOnFollowEvent_NullGuards() {
        assertDoesNotThrow(() -> followFeedEventListener.onFollowEvent(null));
        assertDoesNotThrow(() -> followFeedEventListener.onFollowEvent(new FollowEvent(null, 202L, FollowEvent.FollowAction.FOLLOW)));
        assertDoesNotThrow(() -> followFeedEventListener.onFollowEvent(new FollowEvent(101L, null, FollowEvent.FollowAction.FOLLOW)));
        assertDoesNotThrow(() -> followFeedEventListener.onFollowEvent(new FollowEvent(101L, 202L, null)));

        verifyNoInteractions(articleFeedService);
    }
}
