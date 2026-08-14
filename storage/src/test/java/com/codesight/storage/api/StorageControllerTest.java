package com.codesight.storage.api;

import com.codesight.storage.api.dto.StoragePresignRequest;
import com.codesight.storage.api.dto.StoragePresignResponse;
import com.codesight.storage.service.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StorageControllerTest {

    @Mock
    private StorageService storageService;

    @InjectMocks
    private StorageController storageController;

    @Test
    void presign_ShouldDelegateToServiceAndReturnResponse() {
        StoragePresignRequest request = new StoragePresignRequest("123", "article_content", "text/markdown", null);
        StoragePresignResponse mockResponse = new StoragePresignResponse(
                "posts/123/content.md",
                "https://mock-oss.com/put-url",
                Map.of("Content-Type", "text/markdown"),
                600
        );

        when(storageService.presign(any(StoragePresignRequest.class))).thenReturn(mockResponse);

        StoragePresignResponse result = storageController.presign(request);

        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(storageService).presign(request);
    }
}
