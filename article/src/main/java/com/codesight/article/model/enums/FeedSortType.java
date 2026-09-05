package com.codesight.article.model.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 首页与频道信息流排序方式枚举
 */
@Getter
@AllArgsConstructor
@Schema(description = "信息流排序方式：RECOMMENDED=综合，NEWEST=最新")
public enum FeedSortType {

    RECOMMENDED("recommended", "综合"),
    NEWEST("newest", "最新");

    @JsonValue
    private final String value;
    private final String description;
}
