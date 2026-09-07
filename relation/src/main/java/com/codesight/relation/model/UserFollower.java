package com.codesight.relation.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 用户粉丝实体
 * <p>
 * 主查询维度为 to_user_id，记录真实存在的有效粉丝关系。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_follower")
public class UserFollower {

    /**
     * 主键 ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 被关注的目标用户 ID
     */
    private Long toUserId;

    /**
     * 关注者用户 ID
     */
    private Long fromUserId;

    /**
     * 关注创建时间（自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdTime;
}
