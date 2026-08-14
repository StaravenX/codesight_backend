package com.codesight.storage.service;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.OSSClientBuilder;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.models.PresignResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.codesight.common.exception.BusinessException;
import com.codesight.storage.api.dto.StoragePresignRequest;
import com.codesight.storage.config.OssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * OSS 存储服务测试类
 * <p>
 * 使用 Mockito 拦截并测试 V2 SDK 的静态客户端构建与方法调用
 */
@ExtendWith(MockitoExtension.class)
class OssStorageServiceImplTest {

    private OssStorageServiceImpl storageService;

    @Mock
    private OSSClient mockOss;
    
    @Mock
    private OSSClientBuilder mockBuilder;

    @BeforeEach
    void setUp() {
        OssProperties properties = new OssProperties();
        properties.setRegion("cn-hangzhou");
        properties.setAccessKeyId("test-id");
        properties.setAccessKeySecret("test-secret");
        properties.setBucket("test-bucket");
        properties.setFolder("test-folder");

        storageService = new OssStorageServiceImpl(properties);
    }

    @Test
    void generatePresignedPutUrl_ShouldReturnUrl() {
        String expectedUrl = "https://test-bucket.oss-cn-hangzhou.aliyuncs.com/test.png?Expires=123";
        
        try (MockedStatic<OSSClient> mockedStatic = mockStatic(OSSClient.class)) {
            mockedStatic.when(OSSClient::newBuilder).thenReturn(mockBuilder);
            when(mockBuilder.region(anyString())).thenReturn(mockBuilder);
            when(mockBuilder.credentialsProvider(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockOss);
            
            PresignResult mockResult = PresignResult.newBuilder().url(expectedUrl).build();
            when(mockOss.presign(any(PutObjectRequest.class), any(PresignOptions.class))).thenReturn(mockResult);

            String url = storageService.generatePresignedPutUrl("test.png", "image/png", 3600);

            assertEquals(expectedUrl, url);
            verify(mockOss).presign(any(PutObjectRequest.class), any(PresignOptions.class));
            verify(mockOss).close();
        } catch (Exception e) {
            fail("Exception should not be thrown", e);
        }
    }

    @Test
    void presign_WithArticleContent_ShouldGenerateCorrectObjectKeyAndReturnResponse() {
        String expectedUrl = "https://test-bucket.oss-cn-hangzhou.aliyuncs.com/posts/1001/content.md?Expires=123";
        StoragePresignRequest request = new StoragePresignRequest("1001", "article_content", "text/markdown", null);

        try (MockedStatic<OSSClient> mockedStatic = mockStatic(OSSClient.class)) {
            mockedStatic.when(OSSClient::newBuilder).thenReturn(mockBuilder);
            when(mockBuilder.region(anyString())).thenReturn(mockBuilder);
            when(mockBuilder.credentialsProvider(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockOss);

            PresignResult mockResult = PresignResult.newBuilder().url(expectedUrl).build();
            when(mockOss.presign(any(PutObjectRequest.class), any(PresignOptions.class))).thenReturn(mockResult);

            var response = storageService.presign(request);

            assertNotNull(response);
            assertEquals("posts/1001/content.md", response.objectKey());
            assertEquals(expectedUrl, response.putUrl());
            assertEquals("text/markdown", response.headers().get("Content-Type"));
            assertEquals(600, response.expiresIn());
        } catch (Exception e) {
            fail("Exception should not be thrown", e);
        }
    }

    @Test
    void generatePresignedPutUrl_MissingConfig_ShouldThrowException() {
        OssProperties emptyProps = new OssProperties();
        OssStorageServiceImpl badService = new OssStorageServiceImpl(emptyProps);

        BusinessException exception = assertThrows(BusinessException.class, () -> 
            badService.generatePresignedPutUrl("test.png", "image/png", 3600)
        );

        assertTrue(exception.getMessage().contains("对象存储未配置"));
    }
}
