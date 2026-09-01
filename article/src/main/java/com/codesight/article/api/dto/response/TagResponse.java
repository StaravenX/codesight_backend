package com.codesight.article.api.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 二级技术标签响应体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "二级技术标签响应体")
public class TagResponse {

    @Schema(description = "标签 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "标签名称")
    private String name;

    @Schema(description = "该标签下文章聚合计数")
    private Long articleCount;
}
