package com.codesight.storage.service;

import com.codesight.storage.api.dto.StoragePresignRequest;
import com.codesight.storage.api.dto.StoragePresignResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 统一文件存储抽象接口
 */
public interface StorageService {

    /**
     * 服务端直接上传文件
     *
     * @param objectKey 目标对象路径（包含文件名及后缀）
     * @param file      上传的文件
     * @return 文件的公开访问 URL
     */
    String uploadFile(String objectKey, MultipartFile file);

    /**
     * 根据业务场景生成客户端直传预签名凭证
     *
     * @param request 直传预签名请求
     * @return 预签名响应（包含 objectKey, putUrl, headers, expiresIn）
     */
    StoragePresignResponse presign(StoragePresignRequest request);

    /**
     * 生成用于直传（PUT）的预签名 URL
     *
     * @param objectKey        目标对象路径
     * @param contentType      上传内容类型
     * @param expiresInSeconds 有效期秒数
     * @return 可直接用于 PUT 上传的预签名 URL
     */
    String generatePresignedPutUrl(String objectKey, String contentType, int expiresInSeconds);
}
