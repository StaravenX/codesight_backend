package com.codesight.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 预签名 URL 请求实体
 * <p>
 * 接收客户端获取 OSS PUT 上传凭证的请求参数。
 */
public record StoragePresignRequest(
        /*
          关联的帖子或业务资源 ID（用于隔离文件路径）
         */
        @NotBlank(message = "postId不能为空")
        String postId,
        
        /*
          业务场景，例如: article_content, article_image
         */
        @NotBlank(message = "scene不能为空")
        String scene,
        
        /*
          HTTP MIME Type，如 image/png, text/markdown
         */
        @NotBlank(message = "contentType不能为空")
        String contentType,
        
        /*
          文件扩展名（可选，不传会根据 contentType 推导）
         */
        String ext
) {}
