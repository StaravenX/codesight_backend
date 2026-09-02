package com.codesight.article.model.dto;

import com.codesight.article.api.dto.response.TagResponse;
import com.codesight.article.util.MarkdownParseResult.TocItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * 文章静态元数据缓存传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleDetailStatic implements Serializable {

    private Long id;
    private String title;
    private String summary;
    private String coverUrl;
    private String contentMd;
    private Integer wordCount;
    private Integer readTimeMinutes;
    private List<TocItem> toc;
    private Long categoryId;
    private List<TagResponse> tags;
    private Long authorId;
    private Instant publishTime;
    private Instant updatedTime;
}
