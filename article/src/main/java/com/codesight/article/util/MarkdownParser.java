package com.codesight.article.util;

import com.codesight.article.util.MarkdownParseResult.TocItem;
import lombok.experimental.UtilityClass;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Markdown 提炼工具
 */
@UtilityClass
public class MarkdownParser {

    private static final Parser PARSER = Parser.builder().build();

    /**
     * 解析 Markdown 文本
     */
    public static MarkdownParseResult parse(String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            return new MarkdownParseResult("", Collections.emptyList(), 0, 1);
        }

        StringBuilder textBuilder = new StringBuilder();
        List<TocItem> toc = new ArrayList<>();

        PARSER.parse(contentMd).accept(new AbstractVisitor() {
            private int headingIndex = 1;

            @Override
            public void visit(Text text) {
                textBuilder.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                textBuilder.append(code.getLiteral());
            }

            @Override
            public void visit(Paragraph paragraph) {
                super.visit(paragraph); // 继续遍历段落内部子节点
                textBuilder.append(' ');
            }

            @Override
            public void visit(Heading heading) {
                StringBuilder title = new StringBuilder();
                heading.accept(new AbstractVisitor() {
                    @Override
                    public void visit(Text t) {
                        title.append(t.getLiteral());
                    }

                    @Override
                    public void visit(Code c) {
                        title.append(c.getLiteral());
                    }
                });

                String cleanTitle = title.toString().replaceAll("\\s+", " ").trim();
                if (!cleanTitle.isEmpty()) {
                    toc.add(TocItem.builder()
                            .id("heading-" + headingIndex++)
                            .title(cleanTitle)
                            .level(heading.getLevel())
                            .build());
                }
                textBuilder.append(cleanTitle).append(' ');
            }

            @Override
            public void visit(FencedCodeBlock block) {
                // 忽略代码块
            }

            @Override
            public void visit(IndentedCodeBlock block) {
                // 忽略缩进代码块
            }

            @Override
            public void visit(Image image) {
                // 忽略图片及 URL
            }
        });

        String plainText = textBuilder.toString().replaceAll("\\s+", " ").trim();
        String summary = plainText.length() <= 150 ? plainText : plainText.substring(0, 150).trim() + "...";
        int wordCount = plainText.replace(" ", "").length();
        int readTimeMinutes = Math.max(1, (int) Math.ceil(wordCount / 400.0));

        return new MarkdownParseResult(summary, toc, wordCount, readTimeMinutes);
    }
}
