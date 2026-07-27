package com.codesight.user;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("users")
@Schema(description = "用户实体类")
public class User {
    
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;
    
    @Schema(description = "手机号码")
    private String phone;
    
    @Schema(description = "邮箱地址")
    private String email;
    
    @Schema(description = "密码哈希", hidden = true)
    private String passwordHash;
    
    @Schema(description = "用户昵称")
    private String nickname;
    
    @Schema(description = "用户头像URL")
    private String avatar;
    
    @Schema(description = "个人简介")
    private String bio;
    
    @Schema(description = "平台唯一ID")
    private String csId;
    
    @Schema(description = "性别")
    private String gender;
    
    @Schema(description = "出生日期")
    private LocalDate birthday;
    
    @Schema(description = "就职公司")
    private String company;
    
    @Schema(description = "职位")
    private String jobTitle;
    
    @Schema(description = "院校")
    private String school;
    
    @Schema(description = "感兴趣的技术领域，JSON数组，如 ['Java','Spring','计算机网络','后端']")
    private String interestedDomains;
    
    @Schema(description = "账号创建时间")
    @TableField(fill = FieldFill.INSERT)
    private Instant createdTime;
    
    @Schema(description = "资料最后更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedTime;
}
