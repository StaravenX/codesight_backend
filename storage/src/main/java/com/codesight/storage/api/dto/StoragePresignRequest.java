package com.codesight.storage.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "预签名直传 URL 获取请求")
public record StoragePresignRequest(

        // 关联的帖子或业务资源 ID（用于隔离文件路径）
        @Schema(description = "关联的业务资源 ID (如文章 ID/草稿 ID)")
        @NotBlank(message = "postId不能为空")
        String postId,

        // 业务场景，例如: article_content, article_image
        @Schema(description = "业务场景 (如 article_content, article_image)")
        @NotBlank(message = "scene不能为空")
        String scene,

        // HTTP MIME Type，如 image/png, text/markdown
        @Schema(description = "文件 MIME 类型")
        @NotBlank(message = "contentType不能为空")
        String contentType,

        // 文件扩展名（可选，不传会根据 contentType 推导）
        @Schema(description = "文件扩展名 (可选)")
        String ext

) {}
