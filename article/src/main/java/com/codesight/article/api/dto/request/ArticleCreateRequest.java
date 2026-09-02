package com.codesight.article.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

/**
 * 创建文章或保存草稿请求体
 */
@Builder
@Schema(description = "创建文章或保存草稿请求体")
public record ArticleCreateRequest(
        @Schema(description = "文章标题")
        @NotBlank(message = "文章标题不能为空")
        @Size(max = 256, message = "标题长度不能超过256字符")
        String title,

        @Schema(description = "Markdown 格式正文字符串")
        @NotBlank(message = "文章正文不能为空")
        String contentMd,

        @Schema(description = "所属一级技术分类 ID")
        @NotNull(message = "请选择一级技术分类")
        Long categoryId,

        @Schema(description = "关联的二级技术标签 ID 列表（最多5个）")
        @Size(max = 5, message = "一篇文章最多只能关联5个标签")
        List<Long> tagIds,

        @Schema(description = "文章封面图 URL (可选)")
        String coverUrl,

        @Schema(description = "文章纯文本摘要 (可选，留空则自动从 Markdown 智能提取前 150 字)")
        String summary,

        @Schema(description = "是否保存为草稿（true=存草稿，false=直接公开发布）", defaultValue = "false")
        Boolean isDraft
) {
    public ArticleCreateRequest {
        if (isDraft == null) {
            isDraft = false;
        }
    }
}
