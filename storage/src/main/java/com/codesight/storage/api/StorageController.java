package com.codesight.storage.api;

import com.codesight.common.annotation.RateLimit;
import com.codesight.storage.api.dto.StoragePresignRequest;
import com.codesight.storage.api.dto.StoragePresignResponse;
import com.codesight.storage.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对象存储控制器
 * <p>
 * 处理前端存储相关 HTTP 请求并转发给业务层。
 */
@Tag(name = "对象存储接口", description = "文件存储与预签名相关接口")
@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    /**
     * 获取对象存储直传预签名 URL。
     * <p>
     * 前端上传大文件或文章资源前，先调用此接口获取带有临时写入权限的 OSS URL，
     * 随后由客户端直接发起 PUT 请求上传文件到 OSS，减轻应用服务器带宽压力。
     *
     * @param request 直传预签名请求（包含场景、文章ID、内容类型等）
     * @return 预签名响应（包含 objectKey、putUrl、必须携带的 Headers 及有效时长）
     */
    @Operation(summary = "获取直传预签名URL", description = "客户端通过该URL直接将文件上传到OSS")
    @RateLimit(maxRequests = 30, windowSeconds = 60)
    @PostMapping("/presign")
    public StoragePresignResponse presign(@Valid @RequestBody StoragePresignRequest request) {
        return storageService.presign(request);
    }
}
