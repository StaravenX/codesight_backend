package com.codesight.storage.api.dto;

import java.util.Map;

/**
 * 预签名 URL 响应实体
 * <p>
 * 封装并返回生成好的对象 Key 和供前端直接 PUT 的预签名 URL。
 */
public record StoragePresignResponse(
        /*
          最终生成在 OSS 上的对象路径 (如 posts/123/content.md)
         */
        String objectKey,
        
        /*
          供前端直接发起 PUT 请求上传的预签名直传 URL
         */
        String putUrl,
        
        /*
          前端发起 PUT 请求时必须携带的 Headers
         */
        Map<String, String> headers,
        
        /*
          签名的有效期秒数
         */
        int expiresIn
) {}
