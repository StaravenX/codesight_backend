package com.codesight.article.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分类与标签多对多关联实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("category_tag_rel")
public class CategoryTagRel {

    /**
     * 关联主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属一级技术分类 ID
     */
    private Long categoryId;

    /**
     * 关联二级技术标签 ID
     */
    private Long tagId;
}
