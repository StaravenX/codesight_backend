package com.codesight.article.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文章二级技术标签实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tags")
public class Tag {

    /**
     * 标签主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 标签名称，如：Java, Docker, Vue.js
     */
    private String name;

    /**
     * 标签图标 URL
     */
    private String iconUrl;

    /**
     * 该标签下文章聚合计数
     */
    private Long articleCount;
}
