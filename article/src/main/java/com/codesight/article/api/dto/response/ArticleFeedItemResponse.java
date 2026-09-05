package com.codesight.article.api.dto.response;

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
 * 掘金信息流（Feed）文章卡片响应体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "信息流文章卡片响应体")
public class ArticleFeedItemResponse {

    @Schema(description = "文章 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "文章标题")
    private String title;

    @Schema(description = "文章纯文本摘要")
    private String summary;

    @Schema(description = "文章列表封面图 URL")
    private String coverUrl;

    @Schema(description = "创作者用户 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;

    @Schema(description = "创作者昵称")
    private String authorName;

    @Schema(description = "所属一级分类 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long categoryId;

    @Schema(description = "关联的二级标签列表")
    private List<TagResponse> tags;

    @Schema(description = "首次公开发布时间")
    private Instant publishTime;

    @Schema(description = "实时阅读量 (来自 16B SDS)")
    private Long viewCount;

    @Schema(description = "实时点赞量 (来自 16B SDS)")
    private Long likeCount;

    @Schema(description = "实时评论数 (来自 16B SDS)")
    private Long commentCount;

    @Schema(description = "实时收藏数 (来自 16B SDS)")
    private Long collectCount;

    @Schema(description = "当前登录用户是否已点赞 (来自 4KB 位图)")
    private Boolean isLiked;

    @Schema(description = "创作者主页是否置顶")
    private Boolean isTop;
}
