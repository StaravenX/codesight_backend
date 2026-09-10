package com.codesight.search.index;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * 文章全文检索索引文档模型
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleSearchDoc(
        @JsonProperty("article_id") Long articleId,
        @JsonProperty("title") String title,
        @JsonProperty("body") String body,
        @JsonProperty("summary") String summary,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("author_id") Long authorId,
        @JsonProperty("author_avatar") String authorAvatar,
        @JsonProperty("author_nickname") String authorNickname,
        @JsonProperty("cover_url") String coverUrl,
        @JsonProperty("publish_time") Long publishTime,
        @JsonProperty("like_count") Long likeCount,
        @JsonProperty("favorite_count") Long favoriteCount,
        @JsonProperty("view_count") Long viewCount,
        @JsonProperty("status") String status
) {
}
