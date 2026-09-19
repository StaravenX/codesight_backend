package com.codesight.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 全站知识库 RAG 流式问答请求参数
 */
@Schema(description = "全站知识库 RAG 流式问答请求参数")
public record RagChatRequest(
        @Schema(description = "技术问题描述", example = "Codesight 怎么解决自愈重建在途增量丢失？")
        @NotBlank(message = "提问内容不能为空")
        @Size(max = 500, message = "问题长度不能超过 500 字")
        String question
) {
}
