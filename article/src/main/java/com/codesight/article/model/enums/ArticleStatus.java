package com.codesight.article.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文章生命周期状态枚举
 */
@Getter
@AllArgsConstructor
public enum ArticleStatus {
    DRAFT("draft", "草稿"),
    PUBLISHED("published", "已发布"),
    OFFLINE("offline", "已下架"),
    DELETED("deleted", "已删除");

    @EnumValue
    @JsonValue
    private final String value;
    private final String description;
}
