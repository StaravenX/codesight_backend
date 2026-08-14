package com.codesight.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS 自动配置属性类
 * <p>
 * 从 application.yml 或环境变量中读取 OSS 的配置信息。
 */
@Data
@Component
@ConfigurationProperties(prefix = "oss")
public class OssProperties {
    /**
     * 阿里云 OSS 的 Region，例如: cn-hangzhou
     */
    private String region;
    
    /**
     * RAM 用户的 AccessKeyId
     */
    private String accessKeyId;
    
    /**
     * RAM 用户的 AccessKeySecret
     */
    private String accessKeySecret;
    
    /**
     * 对象存储的 Bucket 名称
     */
    private String bucket;
    
    /**
     * 自定义绑定的公网域名
     */
    private String publicDomain;
    
    /**
     * 默认上传的基础文件夹
     */
    private String folder = "avatars";
}
