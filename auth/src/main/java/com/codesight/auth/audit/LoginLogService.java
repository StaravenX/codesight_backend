package com.codesight.auth.audit;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codesight.auth.audit.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoginLogService extends ServiceImpl<LoginLogMapper, LoginLog> {

    /**
     * 记录一次登录/注册事件。
     *
     * @param userId    用户 ID。
     * @param identifier 登录/注册使用的标识（手机号或邮箱）。
     * @param channel   渠道：PASSWORD/CODE/REGISTER。
     * @param ip        客户端 IP。
     * @param userAgent 客户端 UA。
     * @param status    结果：SUCCESS/FAILED。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(Long userId, String identifier, LoginChannel channel, String ip, String userAgent, LoginStatus status) {
        LoginLog log = LoginLog.builder()
                .userId(userId)
                .identifier(identifier)
                .channel(channel)
                .ip(ip)
                .userAgent(userAgent)
                .status(status)
                .build();
        save(log);
    }
}
