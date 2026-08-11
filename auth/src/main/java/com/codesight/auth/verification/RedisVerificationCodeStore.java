package com.codesight.auth.verification;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
/**
 * 基于 Redis 的验证码存储实现。
 * <p>
 * 使用 Hash 结构保存 `code`、`maxAttempts` 与 `attempts`，TTL 控制有效期。
 * 校验时支持尝试计数与错误状态返回，成功后删除键以防重用。
 */
@Component
public class RedisVerificationCodeStore implements VerificationCodeStore {

    private static final String FIELD_CODE = "code";
    private static final String FIELD_MAX_ATTEMPTS = "maxAttempts";
    private static final String FIELD_ATTEMPTS = "attempts";

    private final DefaultRedisScript<Long> verifyScript;
    private final StringRedisTemplate redisTemplate;

    public RedisVerificationCodeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.verifyScript = new DefaultRedisScript<>();
        this.verifyScript.setLocation(new ClassPathResource("lua/verify_code.lua"));
        this.verifyScript.setResultType(Long.class);
    }

    /**
     * 保存验证码到 Redis Hash，并设置 TTL。
     *
     * @param scene       场景名称。
     * @param identifier  标识（手机号或邮箱）。
     * @param code        验证码字符串。
     * @param ttl         有效期。
     * @param maxAttempts 最大尝试次数。
     * @throws RedisSystemException 保存失败时抛出。
     */
    @Override
    public void saveCode(String scene, String identifier, String code, Duration ttl, int maxAttempts) {
        String key = buildKey(scene, identifier);
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        try {
            String currentAttempts = ops.get(key, FIELD_ATTEMPTS);
            String currentMax = ops.get(key, FIELD_MAX_ATTEMPTS);
            
            if (currentAttempts != null && currentMax != null && 
                Integer.parseInt(currentAttempts) >= Integer.parseInt(currentMax)
            ) {
                throw new BusinessException(ErrorCode.VERIFICATION_LOCKED);
            }

            ops.put(key, FIELD_CODE, code);
            ops.put(key, FIELD_MAX_ATTEMPTS, String.valueOf(maxAttempts));
            
            if (currentAttempts == null) {
                ops.put(key, FIELD_ATTEMPTS, "0");
            }
            
            redisTemplate.expire(key, ttl);
        } catch (DataAccessException ex) {
            throw new RedisSystemException("Failed to save verification code", ex);
        }
    }

    /**
     * 校验验证码是否匹配，更新尝试计数并在成功时删除记录，失败则抛出异常
     *
     * @param scene      场景名称。
     * @param identifier 标识（手机号或邮箱）。
     * @param code       用户输入的验证码。
     */
    @Override
    public void ensureVerified(String scene, String identifier, String code, Duration lockTime) {
        String key = buildKey(scene, identifier);
        
        long result = redisTemplate.execute(
                verifyScript,
                Collections.singletonList(key),
                code,
                String.valueOf(Duration.ofMinutes(30).toSeconds())
        );

        if (result == 0L) {
            throw new BusinessException(ErrorCode.VERIFICATION_MISMATCH);
        } else if (result == -1L) {
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND);
        } else if (result == -2L) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
        }
    }

    /**
     * 生成验证码的 Redis 键名。
     *
     * @param scene      场景名称。
     * @param identifier 标识（手机号或邮箱）。
     * @return 键名字符串。
     */
    private static String buildKey(String scene, String identifier) {
        return "auth:code:%s:%s".formatted(scene, identifier);
    }

}

