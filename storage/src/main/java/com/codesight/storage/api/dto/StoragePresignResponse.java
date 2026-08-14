package com.codesight.storage.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "预签名直传 URL 获取响应")
public record StoragePresignResponse(

        // 最终生成在 OSS 上的对象路径 (如 posts/123/content.md)
        @Schema(description = "OSS 对象存储唯一 Key")
        String objectKey,

        // 供前端直接发起 PUT 请求上传的预签名直传 URL
        @Schema(description = "前端直传 PUT 预签名 URL")
        String putUrl,

        // 前端发起 PUT 请求时必须携带的 Headers
        @Schema(description = "前端发起 PUT 请求时必须携带的 HTTP Headers")
        Map<String, String> headers,

        // 签名的有效期秒数
        @Schema(description = "签名有效时长 (秒)")
        int expiresIn
) {}
