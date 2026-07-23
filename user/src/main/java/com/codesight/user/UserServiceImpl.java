package com.codesight.user;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 根据手机号查询用户。
     * @param phone 手机号。
     * @return 用户
     */
    public User findByPhone(String phone) {
        return lambdaQuery().eq(User::getPhone, phone).one();
    }

    /**
     * 根据邮箱查询用户。
     * @param email 邮箱地址。
     * @return 用户
     */
    public User findByEmail(String email) {
        return lambdaQuery().eq(User::getEmail, email).one();
    }

    /**
     * 判断手机号是否存在。
     * @param phone 手机号。
     * @return 是否存在。
     */
    public boolean existsByPhone(String phone) {
        return lambdaQuery().eq(User::getPhone, phone).exists();
    }

    /**
     * 判断邮箱是否存在。
     * @param email 邮箱地址。
     * @return 是否存在。
     */
    public boolean existsByEmail(String email) {
        return lambdaQuery().eq(User::getEmail, email).exists();
    }

    /**
     * 创建用户，写入创建与更新时间并持久化。
     * @param user 待创建的用户实体。
     */
    @Transactional
    public void createUser(User user) {
        Instant now = Instant.now();
        user.setCreatedTime(now);
        user.setUpdatedTime(now);
        save(user);
    }

}

