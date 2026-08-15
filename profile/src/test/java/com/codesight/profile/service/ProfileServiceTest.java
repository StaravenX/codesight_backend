package com.codesight.profile.service;

import com.codesight.common.exception.BusinessException;
import com.codesight.profile.api.dto.ProfilePatchRequest;
import com.codesight.profile.api.dto.ProfileResponse;
import com.codesight.storage.service.StorageService;
import com.codesight.user.User;
import com.codesight.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 个人资料业务服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private ProfileService profileService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = User.builder()
                .id(1L)
                .nickname("原昵称")
                .csId("geek_old")
                .avatar("old_avatar.png")
                .bio("原简介")
                .gender("MALE")
                .birthday(LocalDate.of(1998, 5, 20))
                .company("原公司")
                .jobTitle("工程师")
                .school("原大学")
                .interestedDomains("[\"Java\"]")
                .build();
    }

    @Test
    void updateProfile_Success() {
        ProfilePatchRequest request = new ProfilePatchRequest(
                "新昵称", "新简介", "FEMALE", null,
                null, null, null, null
        );

        when(userService.getById(1L)).thenReturn(existingUser);
        when(userService.updateById(any(User.class))).thenReturn(true);

        ProfileResponse response = profileService.updateProfile(1L, request);

        assertNotNull(response);
        verify(userService).updateById(any(User.class));
    }

    @Test
    void updateProfile_UserNotFound_ShouldThrowException() {
        ProfilePatchRequest request = new ProfilePatchRequest(
                "新昵称", null, null, null, null, null, null, null
        );

        when(userService.getById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> profileService.updateProfile(999L, request));
    }

    @Test
    void updateProfile_EmptyRequest_ShouldThrowException() {
        ProfilePatchRequest request = new ProfilePatchRequest(
                null, null, null, null, null, null, null, null
        );

        when(userService.getById(1L)).thenReturn(existingUser);

        assertThrows(BusinessException.class, () -> profileService.updateProfile(1L, request));
    }

    @Test
    void uploadAvatar_Success() {
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", "test-bytes".getBytes());

        when(userService.getById(1L)).thenReturn(existingUser);
        when(storageService.uploadFile(anyString(), eq(file))).thenReturn("https://oss.codesight.cn/avatars/1/test.png");
        when(userService.updateById(any(User.class))).thenReturn(true);

        ProfileResponse response = profileService.uploadAvatar(1L, file);

        assertNotNull(response);
        verify(storageService).uploadFile(anyString(), eq(file));
        verify(userService).updateById(any(User.class));
    }

    @Test
    void uploadAvatar_EmptyFile_ShouldThrowException() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "", "image/png", new byte[0]);

        when(userService.getById(1L)).thenReturn(existingUser);

        assertThrows(BusinessException.class, () -> profileService.uploadAvatar(1L, emptyFile));
    }
}
