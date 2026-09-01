package com.codesight.article.api.dto.response;

import com.codesight.article.util.MarkdownParseResult.TocItem;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 文章详情页全量响应体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文章详情页全量响应体")
public class ArticleDetailResponse {

    @Schema(description = "文章 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "文章主标题")
    private String title;

    @Schema(description = "文章纯文本摘要")
    private String summary;

    @Schema(description = "文章封面图 URL")
    private String coverUrl;

    @Schema(description = "Markdown 格式完整正文字符串")
    private String contentMd;

    @Schema(description = "正文字数统计")
    private Integer wordCount;

    @Schema(description = "预估阅读时长 (分钟)")
    private Integer readTimeMinutes;

    @Schema(description = "TOC 目录树节点列表")
    private List<TocItem> toc;

    @Schema(description = "所属一级分类 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long categoryId;

    @Schema(description = "所属一级分类名称")
    private String categoryName;

    @Schema(description = "关联的二级标签列表")
    private List<TagResponse> tags;

    @Schema(description = "创作者用户 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    @Schema(description = "首次公开发布时间")
    private Instant publishTime;

    @Schema(description = "最后更新时间")
    private Instant updatedTime;

    @Schema(description = "实时阅读量 (来自 16B SDS)")
    private Long viewCount;

    @Schema(description = "实时点赞量 (来自 16B SDS)")
    private Long likeCount;

    @Schema(description = "实时评论数 (来自 16B SDS)")
    private Long commentCount;

    @Schema(description = "实时收藏数 (来自 16B SDS)")
    private Long favoriteCount;

    @Schema(description = "当前登录用户是否已点赞 (来自 4KB 位图)")
    @Builder.Default
    private Boolean isLiked = false;

    @Schema(description = "当前登录用户是否已收藏 (来自 4KB 位图)")
    @Builder.Default
    private Boolean isFavorited = false;
}
