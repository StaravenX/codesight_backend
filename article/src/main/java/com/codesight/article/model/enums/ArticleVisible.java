package com.codesight.article.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文章可见性枚举
 */
@Getter
@AllArgsConstructor
public enum ArticleVisible {
    PUBLIC("public", "公开"),
    PRIVATE("private", "仅自己可见");

    @EnumValue
    @JsonValue
    private final String value;
    private final String description;
}
