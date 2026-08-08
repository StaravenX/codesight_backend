package com.codesight.auth.audit.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("login_logs")
public class LoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String identifier;

    private LoginChannel channel;

    private String ip;

    private String userAgent;

    private LoginStatus status;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdTime;
}

