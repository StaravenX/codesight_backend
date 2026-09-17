package com.codesight.article.event;

import com.codesight.article.service.ArticleFeedService;
import com.codesight.relation.event.FollowEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 监听用户关注与取关领域事件，异步触发 Feed 流历史回填与取关清理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowFeedEventListener {

    private final ArticleFeedService articleFeedService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFollowEvent(FollowEvent event) {
        if (event == null || event.fromUserId() == null || event.toUserId() == null || event.action() == null) {
            return;
        }

        Thread.ofVirtual().name("follow-feed-" + event.action() + "-" + event.fromUserId() + "-" + event.toUserId()).start(() -> {
            try {
                switch (event.action()) {
                    case FOLLOW -> articleFeedService.backfillOnFollow(event.fromUserId(), event.toUserId());
                    case UNFOLLOW -> articleFeedService.cleanupOnUnfollow(event.fromUserId(), event.toUserId());
                }
            } catch (Exception e) {
                log.error("处理关注/取关 Feed 异步同步异常: event={}, error={}", event, e.getMessage(), e);
            }
        });
    }
}
