package com.codesight.user;

import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 用户服务接口。
 */
public interface UserService extends IService<User> {

    User findByPhone(String phone);

    User findByEmail(String email);

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    void createUser(User user);

}