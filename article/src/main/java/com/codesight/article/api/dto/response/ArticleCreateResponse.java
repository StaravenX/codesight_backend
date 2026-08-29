package com.codesight.article.api.dto.response;

import com.codesight.article.model.enums.ArticleStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建文章或草稿响应体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "创建文章或草稿响应体")
public class ArticleCreateResponse {

    @Schema(description = "文章唯一分布式 ID")
    private Long id;

    @Schema(description = "文章当前状态：draft=草稿，published=已发布")
    private ArticleStatus status;
}
