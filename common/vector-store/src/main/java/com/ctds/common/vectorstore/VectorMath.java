package com.ctds.common.vectorstore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 向量数学（C-11.2 B1/B3）：归一化 / 余弦 / 零向量 / Top-K 排序（分数降序、key 升序稳定）。 */
public final class VectorMath {

    private static final double ZERO_EPSILON = 1.0e-12;

    private VectorMath() {
    }

    public static float[] normalize(final float[] vector) {
        double sum = 0.0;
        for (final float value : vector) {
            sum += (double) value * value;
        }
        final double norm = Math.sqrt(sum);
        if (norm < ZERO_EPSILON) {
            return new float[vector.length];
        }
        final float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }

    public static double cosine(final float[] a, final float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不一致");
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA < ZERO_EPSILON || normB < ZERO_EPSILON) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static boolean isZero(final float[] vector) {
        for (final float value : vector) {
            if (Math.abs(value) > ZERO_EPSILON) {
                return false;
            }
        }
        return true;
    }

    public static List<VectorHit> rank(final float[] queryVector,
            final Map<String, VectorRecord> records) {
        final List<VectorHit> hits = new ArrayList<>(records.size());
        for (final VectorRecord record : records.values()) {
            if (record.vector().length != queryVector.length) {
                continue;
            }
            hits.add(new VectorHit(record.key(), cosine(queryVector, record.vector())));
        }
        hits.sort(Comparator.comparingDouble(VectorHit::score).reversed()
                .thenComparing(VectorHit::key));
        return List.copyOf(hits);
    }
}
