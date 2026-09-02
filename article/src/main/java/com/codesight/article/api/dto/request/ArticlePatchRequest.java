package com.codesight.article.api.dto.request;

import com.codesight.article.model.enums.ArticleStatus;
import com.codesight.article.model.enums.ArticleVisible;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

/**
 * 修改文章或草稿元数据请求体（支持局部动态更新）
 */
@Builder
@Schema(description = "修改文章请求体")
public record ArticlePatchRequest(
        @Schema(description = "文章标题")
        @Size(max = 256, message = "标题长度不能超过256字符")
        String title,

        @Schema(description = "Markdown 格式正文字符串")
        String contentMd,

        @Schema(description = "所属一级技术分类 ID")
        Long categoryId,

        @Schema(description = "关联的二级技术标签 ID 列表（最多5个）")
        @Size(max = 5, message = "一篇文章最多只能关联5个标签")
        List<Long> tagIds,

        @Schema(description = "文章封面图 URL")
        String coverUrl,

        @Schema(description = "文章纯文本摘要")
        String summary,

        @Schema(description = "创作者主页是否置顶：true=置顶，false=正常")
        Boolean isTop,

        @Schema(description = "可见性：PUBLIC=公开，PRIVATE=仅自己可见")
        ArticleVisible visible,

        @Schema(description = "文章状态：DRAFT=草稿，PUBLISHED=已发布，OFFLINE=已下架，DELETED=已删除")
        ArticleStatus status
) {
}
