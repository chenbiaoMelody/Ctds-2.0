package com.ctds.common.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VectorMathTest {

    @Test
    void cosineShouldHandleIdenticalOrthogonalAndZeroVectors() {
        assertEquals(1.0, VectorMath.cosine(new float[] {1.0f, 0.0f}, new float[] {2.0f, 0.0f}), 1.0e-9);
        assertEquals(0.0, VectorMath.cosine(new float[] {1.0f, 0.0f}, new float[] {0.0f, 1.0f}), 1.0e-9);
        assertEquals(0.0, VectorMath.cosine(new float[] {0.0f, 0.0f}, new float[] {1.0f, 0.0f}), 1.0e-9);
        assertThrows(IllegalArgumentException.class,
                () -> VectorMath.cosine(new float[] {1.0f}, new float[] {1.0f, 2.0f}));
    }

    @Test
    void normalizeAndIsZeroShouldHandleBoundaries() {
        final float[] normalized = VectorMath.normalize(new float[] {3.0f, 4.0f});
        assertEquals(0.6, normalized[0], 1.0e-6);
        assertEquals(0.8, normalized[1], 1.0e-6);
        assertTrue(VectorMath.isZero(VectorMath.normalize(new float[] {0.0f, 0.0f})));
        assertFalse(VectorMath.isZero(normalized));
        assertTrue(VectorMath.isZero(new float[] {5.0e-10f}));
        assertFalse(VectorMath.isZero(new float[] {2.0e-9f}));
        assertTrue(VectorMath.isZero(VectorMath.normalize(new float[] {5.0e-10f})));
    }

    @Test
    void rankShouldSortByScoreDescendingThenKeyAscending() {
        final Map<String, VectorRecord> records = Map.of(
                "b", new VectorRecord("b", "h", new float[] {1.0f, 0.0f}),
                "a", new VectorRecord("a", "h", new float[] {1.0f, 0.0f}),
                "c", new VectorRecord("c", "h", new float[] {0.0f, 1.0f}),
                "bad", new VectorRecord("bad", "h", new float[] {1.0f}));
        final List<VectorHit> hits = VectorMath.rank(new float[] {1.0f, 0.0f}, records);
        assertEquals(List.of("a", "b", "c"), hits.stream().map(VectorHit::key).toList());
        assertEquals(1.0, hits.get(0).score(), 1.0e-9);
        assertEquals(0.0, hits.get(2).score(), 1.0e-9);
    }
}
