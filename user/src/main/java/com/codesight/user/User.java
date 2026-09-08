package com.codesight.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phone;

    private String email;

    private String passwordHash;

    private String nickname;

    private String avatar;

    private String bio;

    private String csId;

    private String jobDirection;

    private String jobTitle;

    private String company;

    private String workDate;

    private String homePage;

    private String interestedDomains;

    private Instant createdTime;

    private Instant updatedTime;
}
