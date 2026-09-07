package com.codesight.relation.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 用户关注实体
 * <p>
 * 主查询维度为 from_user_id，记录真实存在的有效关注关系。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_following")
public class UserFollowing {

    /**
     * 主键 ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 发起关注的用户 ID
     */
    private Long fromUserId;

    /**
     * 被关注的目标用户 ID
     */
    private Long toUserId;

    /**
     * 关注创建时间（自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdTime;
}
