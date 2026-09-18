package com.codesight.article.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 信息流业务容量与阈值配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "codesight.feed")
public class FeedProperties {

    /**
     * 大 V 晋升门槛（粉丝数 >= 此阈值晋升为大 V，走拉模式）
     */
    private long bigVPromotionThreshold = 5500L;

    /**
     * 大 V 降级门槛（粉丝数 < 此阈值跌落为普通博主，走推模式）
     */
    private long bigVDemotionThreshold = 4500L;

    /**
     * 粉丝收件箱最大保留条数
     */
    private int inboxMaxCapacity = 200;

    /**
     * 作者发件箱最大保留条数
     */
    private int outboxMaxCapacity = 100;

    /**
     * 用户推荐流已读曝光最大保留条数
     */
    private int exposedMaxCapacity = 1000;

    /**
     * 用户推荐流已读曝光保留时长
     */
    private Duration exposedTtl = Duration.ofHours(24);
}
