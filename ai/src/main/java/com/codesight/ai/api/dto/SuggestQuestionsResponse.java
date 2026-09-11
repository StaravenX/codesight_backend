package com.codesight.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.Collections;
import java.util.List;

/**
 * 智能技术追问建议响应体
 */
@Builder
@Schema(description = "智能技术追问建议响应体")
public record SuggestQuestionsResponse(
        @Schema(description = "推荐追问列表")
        List<QueryItem> queries
) {
    /**
     * 推荐词条项
     */
    @Schema(description = "推荐词条项")
    public record QueryItem(
            @Schema(description = "追问问题内容")
            String value
    ) {}

    public static SuggestQuestionsResponse of(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new SuggestQuestionsResponse(Collections.emptyList());
        }
        return new SuggestQuestionsResponse(values.stream().map(QueryItem::new).toList());
    }
}
