package com.codesight.auth.audit.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("login_logs")
@Schema(description = "登录审计日志")
public class LoginLog {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "登录标识（手机号或邮箱）")
    private String identifier;

    @Schema(description = "登录渠道（如：REGISTER, PASSWORD, CODE）")
    private LoginChannel channel;

    @Schema(description = "登录IP地址")
    private String ip;

    @Schema(description = "客户端 User-Agent")
    private String userAgent;

    @Schema(description = "登录状态（如：SUCCESS, FAILED）")
    private LoginStatus status;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "记录创建时间")
    private Instant createdAt;
}

