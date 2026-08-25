package com.codesight.article.util;

import com.codesight.article.util.MarkdownParseResult.TocItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarkdownParser 智能提炼引擎单元测试")
class MarkdownParserTest {

    @Test
    @DisplayName("测试提取纯文本摘要：应彻底移除图片、链接语法、粗体与代码块")
    void testExtractSummary() {
        String md = """
                # 架构设计实战
                
                本文介绍 **xxx** 平台的核心架构设计。
                详情可参考 [官方文档](https://xxx.com/docs)。
                
                ![架构图](https://static.xxx.com/arch.png)
                
                ```python
                # 这是一个 Python 代码块
                def hello():
                    print("Hello World")
                ```
                
                > 这是一个关键引用提示。
                
                欢迎大家关注与讨论！
                """;

        MarkdownParseResult result = MarkdownParser.parse(md);
        String summary = result.getSummary();

        assertThat(summary).doesNotContain("#");
        assertThat(summary).doesNotContain("**");
        assertThat(summary).doesNotContain("https://static.xxx.com/arch.png");
        assertThat(summary).doesNotContain("def hello():");
        assertThat(summary).contains("本文介绍 xxx 平台的核心架构设计");
        assertThat(summary).contains("官方文档");
        assertThat(summary).contains("这是一个关键引用提示");
    }

    @Test
    @DisplayName("测试提取 TOC 目录树：应屏蔽代码块内的 # 注释，正确捕获层级")
    void testExtractToc() {
        String md = """
                # 一级标题：系统概览
                
                正文内容...
                
                ```bash
                # 这里是 bash 代码注释，绝不能被解析为标题
                docker run -d kafka
                ```
                
                ## 二级标题：高并发计数设计
                
                ### 三级标题：16B SDS 紧凑存储
                
                正文结束。
                """;

        List<TocItem> toc = MarkdownParser.parse(md).getToc();

        assertThat(toc).hasSize(3);

        assertThat(toc.getFirst().getId()).isEqualTo("heading-1");
        assertThat(toc.get(0).getTitle()).isEqualTo("一级标题：系统概览");
        assertThat(toc.get(0).getLevel()).isEqualTo(1);

        assertThat(toc.get(1).getId()).isEqualTo("heading-2");
        assertThat(toc.get(1).getTitle()).isEqualTo("二级标题：高并发计数设计");
        assertThat(toc.get(1).getLevel()).isEqualTo(2);

        assertThat(toc.get(2).getId()).isEqualTo("heading-3");
        assertThat(toc.get(2).getTitle()).isEqualTo("三级标题：16B SDS 紧凑存储");
        assertThat(toc.get(2).getLevel()).isEqualTo(3);
    }

    @Test
    @DisplayName("测试一站式 parse 解析")
    void testParse() {
        String md = """
                # Spring Boot 3.3 实战
                
                这是一篇文章。
                """;

        MarkdownParseResult result = MarkdownParser.parse(md);

        assertThat(result.getSummary()).isNotEmpty();
        assertThat(result.getToc()).hasSize(1);
        assertThat(result.getWordCount()).isGreaterThan(0);
        assertThat(result.getReadTimeMinutes()).isEqualTo(1);
    }
}
