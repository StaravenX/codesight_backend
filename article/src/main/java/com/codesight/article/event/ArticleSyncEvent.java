package com.codesight.article.event;

/**
 * 文章索引同步事件
 * 用于解耦文章变更与搜索索引同步
 */
public record ArticleSyncEvent(
        Long articleId,
        Action action
) {
    public static final String TOPIC = "article-search-sync";

    public enum Action {
        UPSERT,
        DELETE
    }
}
