package com.codesight.counter.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CounterSchema 16B SDS 编解码与契约单测")
class CounterSchemaTest {

    @Test
    @DisplayName("测试大端序 Int32 编码与解码")
    void testInt32BEEncodingAndDecoding() {
        byte[] buffer = new byte[CounterSchema.TOTAL_BYTES];

        long val1 = 123456789L;
        long val2 = 0L;
        long val3 = 2147483647L; // Integer.MAX_VALUE
        long val4 = 88888L;

        CounterSchema.writeInt32BE(buffer, 0, val1);
        CounterSchema.writeInt32BE(buffer, 4, val2);
        CounterSchema.writeInt32BE(buffer, 8, val3);
        CounterSchema.writeInt32BE(buffer, 12, val4);

        assertEquals(val1, CounterSchema.readInt32BE(buffer, 0));
        assertEquals(val2, CounterSchema.readInt32BE(buffer, 4));
        assertEquals(val3, CounterSchema.readInt32BE(buffer, 8));
        assertEquals(val4, CounterSchema.readInt32BE(buffer, 12));
    }

    @Test
    @DisplayName("测试文章维度 16B SDS decodeSds 解码")
    void testDecodeSdsArticle() {
        byte[] buffer = new byte[CounterSchema.TOTAL_BYTES];
        CounterSchema.writeInt32BE(buffer, CounterSchema.Metric.VIEWS.offset(), 50000L);
        CounterSchema.writeInt32BE(buffer, CounterSchema.Metric.LIKE.offset(), 1200L);
        CounterSchema.writeInt32BE(buffer, CounterSchema.Metric.COMMENT.offset(), 88L);
        CounterSchema.writeInt32BE(buffer, CounterSchema.Metric.FAVORITE.offset(), 420L);

        Map<String, Long> map = CounterSchema.decodeSds("article", buffer);
        assertNotNull(map);
        assertEquals(50000L, map.get("views"));
        assertEquals(1200L, map.get("like"));
        assertEquals(88L, map.get("comment"));
        assertEquals(420L, map.get("favorite"));
    }

    @Test
    @DisplayName("测试用户维度 16B SDS decodeSds 解码")
    void testDecodeSdsUser() {
        byte[] buffer = new byte[CounterSchema.TOTAL_BYTES];
        CounterSchema.writeInt32BE(buffer, CounterSchema.UserMetric.VIEWS_RECEIVED.offset(), 100000L);
        CounterSchema.writeInt32BE(buffer, CounterSchema.UserMetric.LIKES_RECEIVED.offset(), 5500L);
        CounterSchema.writeInt32BE(buffer, CounterSchema.UserMetric.FOLLOWERS.offset(), 320L);
        CounterSchema.writeInt32BE(buffer, CounterSchema.UserMetric.FOLLOWINGS.offset(), 80L);

        Map<String, Long> map = CounterSchema.decodeSds("user", buffer);
        assertNotNull(map);
        assertEquals(100000L, map.get("viewsReceived"));
        assertEquals(5500L, map.get("likesReceived"));
        assertEquals(320L, map.get("followers"));
        assertEquals(80L, map.get("followings"));
    }

    @Test
    @DisplayName("测试非法或空字节数组 decodeSds 应返回 null")
    void testDecodeSdsInvalid() {
        assertNull(CounterSchema.decodeSds("article", null));
        assertNull(CounterSchema.decodeSds("article", new byte[0]));
        assertNull(CounterSchema.decodeSds("article", new byte[10])); // 长度不足 16 字节
        assertNull(CounterSchema.decodeSds("article", new byte[20])); // 长度超过 16 字节
    }

    @Test
    @DisplayName("测试动态指标路由 getMetrics")
    void testGetMetrics() {
        CounterSchema.MetricItem[] articleMetrics = CounterSchema.getMetrics("article");
        assertEquals(4, articleMetrics.length);
        assertEquals("views", articleMetrics[0].getCode());
        assertEquals("like", articleMetrics[1].getCode());
        assertEquals("comment", articleMetrics[2].getCode());
        assertEquals("favorite", articleMetrics[3].getCode());

        CounterSchema.MetricItem[] userMetrics = CounterSchema.getMetrics("user");
        assertEquals(4, userMetrics.length);
        assertEquals("viewsReceived", userMetrics[0].getCode());
        assertEquals("likesReceived", userMetrics[1].getCode());
        assertEquals("followers", userMetrics[2].getCode());
        assertEquals("followings", userMetrics[3].getCode());

        // 未知实体类型默认兜底为 Article 维度
        CounterSchema.MetricItem[] unknownMetrics = CounterSchema.getMetrics("unknown");
        assertEquals(4, unknownMetrics.length);
        assertEquals("views", unknownMetrics[0].getCode());
    }
}
