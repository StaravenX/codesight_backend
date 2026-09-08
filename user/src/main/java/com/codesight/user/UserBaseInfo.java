package com.codesight.user;

import java.io.Serializable;

/**
 * 用户基础画像传输对象
 */
public record UserBaseInfo(
        Long id,
        String nickname,
        String avatar,
        String bio,
        String jobTitle,
        String company
) implements Serializable {
    
    public static UserBaseInfo from(User user) {
        return user != null ? new UserBaseInfo(
                user.getId(),
                user.getNickname(),
                user.getAvatar(),
                user.getBio(),
                user.getJobTitle(),
                user.getCompany()
        ) : null;
    }
}
