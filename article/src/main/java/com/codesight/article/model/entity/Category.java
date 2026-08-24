package com.codesight.article.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文章一级技术分类实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("categories")
public class Category {

    /**
     * 分类主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 分类名称，如：后端、前端、人工智能
     */
    private String name;

    /**
     * 英文路由标识，如：backend, frontend, ai
     */
    private String slug;

    /**
     * 排序权重（升序）
     */
    private Integer sortOrder;

    /**
     * 分类图标 URL
     */
    private String iconUrl;
}
