package com.codesight.article.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import com.codesight.article.util.MarkdownParseResult.TocItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 文章核心持久化实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "articles", autoResultMap = true)
public class Article {

    /**
     * 文章全局分布式 ID（雪花算法生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 创作者用户 ID
     */
    private Long authorId;

    /**
     * 所属一级技术分类 ID
     */
    private Long categoryId;

    /**
     * 文章主标题
     */
    private String title;

    /**
     * 文章摘要
     */
    private String summary;

    /**
     * 文章列表封面图 URL
     */
    private String coverUrl;

    /**
     * Markdown 格式正文字符串
     */
    private String contentMd;

    /**
     * 正文字数统计
     */
    private Integer wordCount;

    /**
     * 预估阅读时长（单位：分钟）
     */
    private Integer readTimeMinutes;

    /**
     * Markdown AST 提炼的目录导航树（JSON 格式持久化存储）
     */
    @TableField(value = "toc_json", typeHandler = JacksonTypeHandler.class)
    private List<TocItem> toc;

    /**
     * 阅读量统计
     */
    @Builder.Default
    private Long viewCount = 0L;

    /**
     * 点赞量统计
     */
    @Builder.Default
    private Long likeCount = 0L;

    /**
     * 评论量统计
     */
    @Builder.Default
    private Long commentCount = 0L;

    /**
     * 收藏量统计
     */
    @Builder.Default
    private Long favoriteCount = 0L;

    /**
     * 综合推荐排序分
     */
    @Builder.Default
    private Double rankScore = 0.0;

    /**
     * 创作者主页是否置顶：true=置顶，false=正常
     */
    private Boolean isTop;

    /**
     * 可见性：PUBLIC=公开，PRIVATE=仅自己可见
     */
    private ArticleVisible visible;

    /**
     * 文章状态：DRAFT=草稿，PUBLISHED=已发布，OFFLINE=已下架，DELETED=已删除
     */
    private ArticleStatus status;

    /**
     * 首次公开发布时间
     */
    private Instant publishTime;

    /**
     * 记录创建时间（自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdTime;

    /**
     * 记录更新时间（自动填充）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedTime;
}
