package com.codesight.relation.event;

/**
 * 关注与取关领域事件
 */
public record FollowEvent(
        Long fromUserId,
        Long toUserId,
        FollowAction action
) {
    public enum FollowAction {
        FOLLOW,
        UNFOLLOW
    }
}
