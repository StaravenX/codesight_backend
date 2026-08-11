package com.codesight.auth.service;

import com.codesight.auth.config.AuthProperties;
import com.codesight.common.exception.BusinessException;
import com.codesight.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录安全防护服务。
 * <p>
 * 基于 Redis 实现双维度（账号 + IP）的登录失败计数与锁定机制，
 * 防止暴力破解与撞库攻击。
 */
@Service
@RequiredArgsConstructor
public class LoginSecurityService {

    private static final String ACCOUNT_FAIL_KEY = "auth:login:fail:account:";
    private static final String IP_FAIL_KEY = "auth:login:fail:ip:";
    private static final String ACCOUNT_LOCK_KEY = "auth:login:lock:account:";
    private static final String IP_LOCK_KEY = "auth:login:lock:ip:";

    private final StringRedisTemplate stringRedisTemplate;
    private final AuthProperties authProperties;

    /**
     * 检查账号或 IP 是否已被锁定。
     * <p>
     * 在密码校验之前调用，若任一维度处于锁定状态则直接拒绝。
     *
     * @param identifier 用户标识（手机号或邮箱）。
     * @param ip         客户端 IP 地址。
     * @throws BusinessException 如果账号或 IP 已被锁定。
     */
    public void checkIsLocked(String identifier, String ip) {
        if (stringRedisTemplate.hasKey(ACCOUNT_LOCK_KEY + identifier)) {
            throw new BusinessException(ErrorCode.LOGIN_LOCKED, "该账号因登录失败次数过多已被临时锁定，请稍后再试");
        }
        if (stringRedisTemplate.hasKey(IP_LOCK_KEY + ip)) {
            throw new BusinessException(ErrorCode.LOGIN_LOCKED, "当前网络因登录失败次数过多已被临时锁定，请稍后再试");
        }
    }

    /**
     * 记录一次登录失败，并在超限时触发锁定。
     * <p>
     * 仅在密码校验失败后调用。使用 INCR + EXPIRE 实现滑动窗口计数，
     * 超限后写入 lock key 并清除 fail 计数器。
     *
     * @param identifier 用户标识（手机号或邮箱）。
     * @param ip         客户端 IP 地址。
     */
    public void recordFailure(String identifier, String ip) {
        AuthProperties.Login cfg = authProperties.getLogin();

        // 账号维度计数
        incrementAndLockIfExceeded(
                ACCOUNT_FAIL_KEY + identifier,
                ACCOUNT_LOCK_KEY + identifier,
                cfg.getMaxAccountAttempts(),
                cfg.getAccountLockTime()
        );

        // IP 维度计数
        incrementAndLockIfExceeded(
                IP_FAIL_KEY + ip,
                IP_LOCK_KEY + ip,
                cfg.getMaxIpAttempts(),
                cfg.getIpLockTime()
        );
    }

    /**
     * 登录成功后清除该账号和 IP 的失败计数。
     *
     * @param identifier 用户标识（手机号或邮箱）。
     * @param ip         客户端 IP 地址。
     */
    public void clearFailure(String identifier, String ip) {
        stringRedisTemplate.delete(ACCOUNT_FAIL_KEY + identifier);
        stringRedisTemplate.delete(IP_FAIL_KEY + ip);
    }

    /**
     * 对指定 key 执行 INCR 计数，首次写入时设置过期时间；
     * 若累计次数超过阈值，则写入锁定 key 并删除计数 key。
     *
     * @param failKey     失败计数的 Redis Key。
     * @param lockKey     锁定标记的 Redis Key。
     * @param maxAttempts 最大允许失败次数。
     * @param lockTime    锁定持续时间。
     */
    private void incrementAndLockIfExceeded(String failKey, String lockKey, int maxAttempts, Duration lockTime) {
        Long count = stringRedisTemplate.opsForValue().increment(failKey);
        if (count == null) {
            return;
        }
        if (count == 1L) {
            stringRedisTemplate.expire(failKey, lockTime);
        }
        if (count >= maxAttempts) {
            stringRedisTemplate.opsForValue().set(lockKey, "1", lockTime);
            stringRedisTemplate.delete(failKey);
        }
    }
}

