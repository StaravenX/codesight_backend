package com.codesight.article.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 文章与标签多对多关联实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("article_tag_rel")
public class ArticleTagRel {

    /**
     * 关联主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联文章 ID
     */
    private Long articleId;

    /**
     * 关联标签 ID
     */
    private Long tagId;

    /**
     * 关联创建时间（自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdTime;
}
