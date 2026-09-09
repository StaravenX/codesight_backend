package com.codesight.article.api.dto.request;

import com.codesight.article.model.enums.FeedSortType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * 信息流与分类文章游标分页查询参数
 */
@Builder
@Schema(description = "信息流游标分页查询参数")
public record ArticleFeedRequest(
        @Schema(description = "一级技术分类 ID（可选，按分类过滤）")
        Long categoryId,

        @Schema(description = "二级技术标签 ID（可选，按标签聚合过滤）")
        Long tagId,

        @Schema(description = "创作者用户 ID（可选，查询指定作者的文章列表）")
        Long authorId,

        @Schema(description = "游标（上一页最后一条文章的时间戳或标识）")
        String cursor,

        @Schema(description = "每页拉取条数", defaultValue = "20")
        @Min(value = 1, message = "每页条数最小为1")
        @Max(value = 50, message = "每页条数最大为50")
        @NotNull(message = "每页条数不能为空")
        Integer size,

        @Schema(description = "排序方式：RECOMMENDED=综合推荐，NEWEST=最新发布，FOLLOWING=社交关注", defaultValue = "RECOMMENDED")
        FeedSortType sortBy
) {
}

