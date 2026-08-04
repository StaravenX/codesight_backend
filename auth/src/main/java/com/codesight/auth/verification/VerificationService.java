package com.codesight.auth.verification;

import com.codesight.auth.model.IdentifierType;
import com.codesight.auth.verification.model.*;

import lombok.RequiredArgsConstructor;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.codesight.auth.config.AuthProperties;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;

import cn.hutool.core.util.RandomUtil;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 验证码业务服务。
 * <p>
 * 负责发送与校验验证码：
 * - 速率限制与日限额；
 * - 随机码生成与存储；
 * - 调用发送器进行实际发送；
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final VerificationCodeStore codeManager;
    private final CodeSender codeSender;
    private final StringRedisTemplate stringRedisTemplate;
    private final AuthProperties properties;

    /**
     * 发送验证码到指定标识。
     * <p>
     * 执行发送间隔与日次数限制，生成随机数字验证码，保存到存储并调用发送器。
     *
     * @param scene      验证码场景（REGISTER/LOGIN/RESET_PASSWORD）。
     * @param identifier 标识（手机号或邮箱）。
     * @param type       标示类型
     * @return 发送结果，包含标识、场景与过期秒数。
     * @throws BusinessException 参数不完整或触发速率/日限额时抛出。
     */
    public SendCodeResult sendCode(VerificationScene scene, String identifier, IdentifierType type) {
        Assert.notNull(scene, "scene must not be null");
        Assert.notNull(type, "type must not be null");
        Assert.hasText(identifier, "identifier must not be empty");
        AuthProperties.Verification cfg = properties.getVerification();
        enforceSendInterval(scene, identifier, cfg.getSendInterval());
        enforceDailyLimit(scene, identifier, cfg.getDailyLimit());

        String code = RandomUtil.randomNumbers(cfg.getCodeLength());
        codeManager.saveCode(scene.name(), identifier, code, cfg.getTtl(), cfg.getMaxAttempts());
        codeSender.sendCode(scene.name(), type, identifier, code, (int) cfg.getTtl().toMinutes());
        return new SendCodeResult(identifier, scene, (int) cfg.getTtl().toSeconds());
    }

    /**
     * 校验验证码是否正确且未超限。
     *
     * @param scene      验证码场景。
     * @param identifier 标识（手机号或邮箱）。
     * @param code       用户输入的验证码。
     * @return 校验结果，包含状态与尝试次数统计。
     * @throws BusinessException 参数不完整时抛出。
     */
    public VerificationCheckResult verifyCode(VerificationScene scene, String identifier, String code) {
        Assert.notNull(scene, "scene must not be null");
        Assert.hasText(identifier, "identifier must not be empty");
        Assert.hasText(code, "code must not be empty");
        return codeManager.verifyCode(scene.name(), identifier, code);
    }

    /**
     * 发送间隔限制：同一标识在指定间隔内只能发送一次。
     *
     * @param scene      验证码场景。
     * @param identifier 标识（手机号或邮箱）。
     * @param interval   发送间隔。
     */
    private void enforceSendInterval(VerificationScene scene, String identifier, Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        String key = "auth:code:last:" + scene.name() + ":" + identifier;
        if (Boolean.FALSE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", interval))) {
            throw new BusinessException(ErrorCode.VERIFICATION_RATE_LIMIT);
        }
    }

    /**
     * 每日发送次数限制：超过上限则抛出限额异常。
     *
     * @param scene      验证码场景。
     * @param identifier 标识（手机号或邮箱）。
     * @param limit      每日上限次数。
     */
    private void enforceDailyLimit(VerificationScene scene, String identifier, int limit) {
        if (limit <= 0) {
            return;
        }
        String date = DAY_FORMAT.format(LocalDate.now());
        String key = "auth:code:count:" + scene.name() + ":" + identifier + ":" + date;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        // 第一次发送
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofDays(1));
        }
        if (count != null && count > limit) {
            throw new BusinessException(ErrorCode.VERIFICATION_DAILY_LIMIT);
        }
    }
}
