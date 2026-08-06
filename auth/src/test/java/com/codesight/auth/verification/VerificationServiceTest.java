package com.codesight.auth.verification;

import com.codesight.auth.config.AuthProperties;
import com.codesight.auth.model.IdentifierType;
import com.codesight.auth.verification.model.SendCodeResult;
import com.codesight.auth.verification.model.VerificationScene;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// 启用mock注解
@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    // 创建 mock 对象
    @Mock
    private VerificationCodeStore codeStore;

    @Mock
    private CodeSender codeSender;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AuthProperties properties;

    // 创建真实被测试对象，并注入Mock对象
    @InjectMocks
    private VerificationService verificationService;

    private AuthProperties.Verification mockConfig;

    // 在每个test方法执行前都会执行，初始化 mock 对象
    @BeforeEach
    void setUp() {
        mockConfig = new AuthProperties.Verification();
        mockConfig.setSendInterval(Duration.ofSeconds(60));
        mockConfig.setDailyLimit(5);
        mockConfig.setCodeLength(6);
        mockConfig.setTtl(Duration.ofMinutes(5));
        mockConfig.setMaxAttempts(5);

        lenient().when(properties.getVerification()).thenReturn(mockConfig);
    }

    @Test
    void testSendCode_Success() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // 模拟未在冷却期内 (setIfAbsent 成功返回 true)
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        // 模拟当日第一次发送
        when(valueOperations.increment(anyString())).thenReturn(1L);

        SendCodeResult result = verificationService.sendCode(VerificationScene.LOGIN, "test@example.com", IdentifierType.EMAIL);

        assertNotNull(result);
        assertEquals("test@example.com", result.identifier());
        assertEquals(VerificationScene.LOGIN, result.scene());
        assertEquals(300, result.expireSeconds());

        // 验证确实调用了底层存储和发送器
        verify(codeStore, times(1)).saveCode(eq("LOGIN"), eq("test@example.com"), anyString(), eq(mockConfig.getTtl()), eq(5));
        verify(codeSender, times(1)).sendCode(eq("LOGIN"), eq(IdentifierType.EMAIL), eq("test@example.com"), anyString(), eq(5));
    }

    @Test
    void testSendCode_RateLimitExceeded() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // 模拟仍在冷却期内 (setIfAbsent 失败返回 false)
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                verificationService.sendCode(VerificationScene.LOGIN, "test@example.com", IdentifierType.EMAIL));

        assertEquals(ErrorCode.VERIFICATION_RATE_LIMIT, ex.getErrorCode());

        // 确保没有调用后续的存储和发送逻辑
        verify(codeStore, never()).saveCode(anyString(), anyString(), anyString(), any(Duration.class), anyInt());
        verify(codeSender, never()).sendCode(anyString(), any(IdentifierType.class), anyString(), anyString(), anyInt());
    }

    @Test
    void testSendCode_DailyLimitExceeded() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // 模拟不在冷却期
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        // 触发每日上限
        when(valueOperations.increment(anyString())).thenReturn(6L);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                verificationService.sendCode(VerificationScene.LOGIN, "test@example.com", IdentifierType.EMAIL));

        assertEquals(ErrorCode.VERIFICATION_DAILY_LIMIT, ex.getErrorCode());

        // 确保没有调用后续的存储和发送逻辑
        verify(codeStore, never()).saveCode(anyString(), anyString(), anyString(), any(Duration.class), anyInt());
    }

    @Test
    void testEnsureVerified_Success() {
        doNothing().when(codeStore).ensureVerified("LOGIN", "test@example.com", "123456");

        assertDoesNotThrow(() ->
                verificationService.ensureVerified(VerificationScene.LOGIN, "test@example.com", "123456")
        );

        verify(codeStore, times(1)).ensureVerified("LOGIN", "test@example.com", "123456");
    }
}
