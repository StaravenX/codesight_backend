package com.codesight.storage.service;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.PresignResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import com.codesight.storage.api.dto.StoragePresignRequest;
import com.codesight.storage.api.dto.StoragePresignResponse;
import com.codesight.storage.config.OssProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 阿里云 OSS 存储服务实现类
 * <p>
 * 支持服务端直接上传，以及预签名直传机制。
 */
@Service
@RequiredArgsConstructor
public class OssStorageServiceImpl implements StorageService {

    private final OssProperties props;

    /**
     * 服务端直接上传文件到 OSS
     *
     * @param objectKey 目标对象路径
     * @param file      前端传来的 MultipartFile
     * @return 上传成功后的公网可访问 URL
     */
    @Override
    public String uploadFile(String objectKey, MultipartFile file) {
        ensureConfigured();
        try (OSSClient client = OSSClient.newBuilder()
                .region(props.getRegion())
                .credentialsProvider(new StaticCredentialsProvider(props.getAccessKeyId(), props.getAccessKeySecret()))
                .build()) {
            
            PutObjectRequest request = PutObjectRequest.newBuilder()
                    .bucket(props.getBucket())
                    .key(objectKey)
                    .body(BinaryData.fromStream(file.getInputStream()))
                    .build();
            client.putObject(request);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件读取或上传失败");
        }

        return publicUrl(objectKey);
    }

    /**
     * 根据业务场景生成客户端直传预签名凭证
     *
     * @param request 直传预签名请求
     * @return 预签名响应（包含 objectKey, putUrl, headers, expiresIn）
     */
    @Override
    public StoragePresignResponse presign(StoragePresignRequest request) {
        String objectKey = generateObjectKey(request);
        int expiresIn = 600;

        String putUrl = generatePresignedPutUrl(objectKey, request.contentType(), expiresIn);
        Map<String, String> headers = Map.of("Content-Type", request.contentType());

        return new StoragePresignResponse(objectKey, putUrl, headers, expiresIn);
    }

    /**
     * 根据不同的上传场景和扩展名生成唯一的 ObjectKey
     *
     * @param request 预签名请求对象
     * @return 最终在 OSS 上的文件路径（含扩展名）
     */
    private String generateObjectKey(StoragePresignRequest request) {
        String ext = normalizeExt(request.ext(), request.contentType());
        String postId = request.postId();

        return switch (request.scene()) {
            case "article_content" -> "posts/" + postId + "/content" + ext;
            case "article_image" -> {
                String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("UTC")).format(Instant.now());
                String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                yield "posts/" + postId + "/images/" + date + "/" + rand + ext;
            }
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上传场景: " + request.scene());
        };
    }

    /**
     * 规范化文件扩展名
     * <p>
     * 如果前端没有传入扩展名，则尝试通过 contentType 自动推导。
     *
     * @param ext         前端传入的扩展名
     * @param contentType 文件的 MIME 类型
     * @return 规范化后带点的扩展名，如 ".png"
     */
    private String normalizeExt(String ext, String contentType) {
        if (ext != null && !ext.isBlank()) {
            return ext.startsWith(".") ? ext : "." + ext;
        }

        if (contentType == null) return ".bin";
        if (contentType.startsWith("image/")) {
            return "." + contentType.substring(6);
        }
        return switch (contentType) {
            case "text/markdown" -> ".md";
            case "text/html" -> ".html";
            case "text/plain" -> ".txt";
            case "application/json" -> ".json";
            default -> ".bin";
        };
    }

    /**
     * 生成供客户端直传使用的 PUT 预签名 URL
     *
     * @param objectKey        目标对象路径
     * @param contentType      必须匹配的 MIME Type
     * @param expiresInSeconds 签名有效期（秒）
     * @return 预签名 URL
     */
    @Override
    public String generatePresignedPutUrl(String objectKey, String contentType, int expiresInSeconds) {
        ensureConfigured();
        try (OSSClient client = OSSClient.newBuilder()
                .region(props.getRegion())
                .credentialsProvider(new StaticCredentialsProvider(props.getAccessKeyId(), props.getAccessKeySecret()))
                .build()) {
            
            PresignOptions presignOptions = PresignOptions.newBuilder()
                    .expiration(Duration.ofSeconds(expiresInSeconds))
                    .build();
            
            PutObjectRequest.Builder requestBuilder = PutObjectRequest.newBuilder()
                    .bucket(props.getBucket())
                    .key(objectKey);
            
            if (contentType != null && !contentType.isBlank()) {
                requestBuilder.contentType(contentType);
            }
            
            PresignResult result = client.presign(requestBuilder.build(), presignOptions);
            return result.url();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "生成预签名URL失败");
        }
    }

    /**
     * 拼接并返回对象的外网访问 URL
     *
     * @param objectKey 对象路径
     * @return 完整的 URL 链接
     */
    private String publicUrl(String objectKey) {
        if (props.getPublicDomain() != null && !props.getPublicDomain().isBlank()) {
            return props.getPublicDomain().replaceAll("/$", "") + "/" + objectKey;
        }
        return "https://" + props.getBucket() + ".oss-" + props.getRegion() + ".aliyuncs.com/" + objectKey;
    }

    /**
     * 校验配置类参数是否完整
     */
    private void ensureConfigured() {
        if (props.getRegion() == null || props.getAccessKeyId() == null || props.getAccessKeySecret() == null || props.getBucket() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "对象存储未配置");
        }
    }
}
