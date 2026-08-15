package com.codesight.profile.api;

import com.codesight.profile.api.dto.ProfilePatchRequest;
import com.codesight.profile.api.dto.ProfileResponse;
import com.codesight.profile.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个人资料接口控制器单元测试
 */
@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

    @Mock
    private ProfileService profileService;

    @InjectMocks
    private ProfileController profileController;

    @Test
    void patch_ShouldPassUserIdToServiceAndReturnResponse() {
        ProfilePatchRequest request = new ProfilePatchRequest(
                "新昵称", "新简介", "MALE", LocalDate.of(2000, 1, 1),
                "测试公司", "架构师", "测试大学", "[\"Java\"]"
        );
        ProfileResponse mockResponse = new ProfileResponse(
                1L, "新昵称", "avatar.png", "新简介", "geek_test",
                "MALE", LocalDate.of(2000, 1, 1), "测试公司", "架构师",
                "测试大学", "13800000000", "test@codesight.cn", "[\"Java\"]", null
        );

        when(profileService.updateProfile(eq(1L), any(ProfilePatchRequest.class))).thenReturn(mockResponse);

        ProfileResponse result = profileController.patch(1L, request);

        assertNotNull(result);
        assertEquals("新昵称", result.nickname());
        verify(profileService).updateProfile(1L, request);
    }

    @Test
    void uploadAvatar_ShouldPassUserIdToServiceAndReturnResponse() {
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", "test-bytes".getBytes());
        ProfileResponse mockResponse = new ProfileResponse(
                1L, "name", "https://oss.codesight.cn/avatars/1/test.png", "简介",
                "geek_user", "MALE", null, null, null, null, null, null, null, null
        );

        when(profileService.uploadAvatar(eq(1L), eq(file))).thenReturn(mockResponse);

        ProfileResponse result = profileController.uploadAvatar(1L, file);

        assertNotNull(result);
        assertEquals("https://oss.codesight.cn/avatars/1/test.png", result.avatar());
        verify(profileService).uploadAvatar(1L, file);
    }
}
