package com.codesight.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch 基础设施连接属性
 */
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
}
