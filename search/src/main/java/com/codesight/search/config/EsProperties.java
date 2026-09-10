package com.codesight.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "spring.elasticsearch")
public class EsProperties {
    /**
     * ES 节点 URI
     */
    private String uri;

    /**
     * 账号
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 默认文章搜索索引名
     */
    private String index = "codesight_article_index";

}
