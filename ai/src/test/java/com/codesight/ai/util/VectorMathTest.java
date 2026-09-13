package com.codesight.ai.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorMathTest {

    @Test
    @DisplayName("测试同向向量：余弦相似度应为 1.0")
    void testIdenticalVectors() {
        float[] a = new float[]{1.0f, 2.0f, 3.0f};
        float[] b = new float[]{2.0f, 4.0f, 6.0f};

        double sim = VectorMath.cosineSimilarity(a, b);
        assertEquals(1.0, sim, 1e-6);
    }

    @Test
    @DisplayName("测试正交垂直向量：余弦相似度应为 0.0")
    void testOrthogonalVectors() {
        float[] a = new float[]{1.0f, 0.0f, 0.0f};
        float[] b = new float[]{0.0f, 1.0f, 0.0f};

        double sim = VectorMath.cosineSimilarity(a, b);
        assertEquals(0.0, sim, 1e-6);
    }

    @Test
    @DisplayName("测试相反反向向量：余弦相似度应为 -1.0")
    void testOppositeVectors() {
        float[] a = new float[]{1.0f, 1.0f};
        float[] b = new float[]{-1.0f, -1.0f};

        double sim = VectorMath.cosineSimilarity(a, b);
        assertEquals(-1.0, sim, 1e-6);
    }

    @Test
    @DisplayName("测试近邻同质文本向量相似度识别（高于 0.85）")
    void testHighSimilarityVectors() {
        // 软文 A 与软文 B 的高维模拟向量（微小扰动）
        float[] softArticleA = new float[]{0.6f, 0.5f, 0.4f, 0.3f};
        float[] softArticleB = new float[]{0.58f, 0.52f, 0.39f, 0.31f};

        double sim = VectorMath.cosineSimilarity(softArticleA, softArticleB);
        assertTrue(sim > 0.95, "同质软文向量相似度应大于 0.95，实际为: " + sim);
    }

    @Test
    @DisplayName("测试向量聚合与归一化：多向量均值与 L2 模长为 1.0")
    void testAggregateAndNormalize() {
        float[] v1 = new float[]{1.0f, 0.0f};
        float[] v2 = new float[]{0.0f, 1.0f};

        float[] aggregated = VectorMath.aggregateAndNormalize(List.of(v1, v2));
        assertNotNull(aggregated);
        assertEquals(2, aggregated.length);

        double norm = Math.sqrt(aggregated[0] * aggregated[0] + aggregated[1] * aggregated[1]);
        assertEquals(1.0, norm, 1e-6);
        assertEquals(aggregated[0], aggregated[1], 1e-6);

        // 边界保护
        assertArrayEquals(new float[0], VectorMath.aggregateAndNormalize(null));
        assertArrayEquals(new float[0], VectorMath.aggregateAndNormalize(List.of()));
    }
}

