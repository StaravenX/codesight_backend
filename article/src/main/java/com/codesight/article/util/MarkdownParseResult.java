package com.codesight.article.util;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Markdown 智能解析提炼结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarkdownParseResult {

    /** 纯文本摘要 */
    private String summary;

    /** 目录树列表 */
    private List<TocItem> toc;

    /** 正文有效字数统计 */
    private int wordCount;

    /** 预估阅读时长（单位：分钟） */
    private int readTimeMinutes;

    /**
     * Markdown TOC 目录树节点
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "文章 TOC 目录树节点")
    public static class TocItem {

        @Schema(description = "目录锚点 ID")
        private String id;

        @Schema(description = "标题文本")
        private String title;

        @Schema(description = "标题层级（1=H1, 2=H2, 3=H3）")
        private int level;
    }
}
