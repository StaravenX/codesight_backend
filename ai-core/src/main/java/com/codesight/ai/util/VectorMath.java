package com.codesight.ai.util;

import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量数学计算工具类
 */
@UtilityClass
public final class VectorMath {
    /**
     * 计算两个浮点数组的余弦相似度
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 余弦相似度 (-1.0 ~ 1.0)，非法入参或零向量时返回 0.0
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            float va = a[i];
            float vb = b[i];
            dot += va * vb;
            normA += va * va;
            normB += vb * vb;
        }

        if (normA <= 1e-12 || normB <= 1e-12) {
            return 0.0;
        }

        double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        if (Double.isNaN(similarity)) {
            return 0.0;
        }
        return Math.clamp(similarity, -1.0, 1.0);
    }

    /**
     * 聚合多个向量并进行 L2 归一化
     */
    public static float[] aggregateAndNormalize(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return new float[0];
        }

        // 选取维度众数
        Map<Integer, Integer> dimCounts = new HashMap<>();
        int targetDim = 0;
        int maxCount = 0;
        for (float[] v : vectors) {
            if (v != null && v.length > 0) {
                int count = dimCounts.merge(v.length, 1, Integer::sum);
                if (count > maxCount) {
                    maxCount = count;
                    targetDim = v.length;
                }
            }
        }
        if (targetDim == 0) {
            return new float[0];
        }

        float[] sum = new float[targetDim];
        boolean valid = false;
        for (float[] v : vectors) {
            if (v != null && v.length == targetDim) {
                for (int i = 0; i < targetDim; i++) {
                    sum[i] += v[i];
                }
                valid = true;
            }
        }

        if (!valid) {
            return new float[0];
        }
        double normSq = 0.0;
        for (float v : sum) {
            normSq += v * v;
        }
        double norm = Math.sqrt(normSq);
        if (norm <= 1e-12) {
            return sum.clone();
        }
        float[] normalized = new float[sum.length];
        for (int i = 0; i < sum.length; i++) {
            normalized[i] = (float) (sum[i] / norm);
        }
        return normalized;
    }
}