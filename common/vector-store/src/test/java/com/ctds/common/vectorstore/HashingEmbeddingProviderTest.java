package com.ctds.common.vectorstore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HashingEmbeddingProviderTest {

    private final HashingEmbeddingProvider provider = new HashingEmbeddingProvider();

    @Test
    void shouldBeDeterministicWithFixedDimensions() {
        final float[] first = provider.embed("金融风控数据");
        final float[] second = new HashingEmbeddingProvider().embed("金融风控数据");
        assertEquals(HashingEmbeddingProvider.DEFAULT_DIMENSIONS, provider.dimensions());
        assertEquals(HashingEmbeddingProvider.DEFAULT_DIMENSIONS, first.length);
        assertArrayEquals(first, second, 0.0f);
    }

    @Test
    void shouldNormalizeVectorToUnitLength() {
        final float[] vector = provider.embed("金融风控");
        double sum = 0.0;
        for (final float value : vector) {
            sum += (double) value * value;
        }
        assertEquals(1.0, Math.sqrt(sum), 1.0e-5);
    }

    @Test
    void shouldReturnZeroVectorForNoTokens() {
        assertTrue(VectorMath.isZero(provider.embed("，。！？  ")));
        assertTrue(VectorMath.isZero(provider.embed(null)));
    }

    @Test
    void shouldIgnoreAsciiCaseAndFullWidth() {
        assertArrayEquals(provider.embed("API接口"), provider.embed("ａｐｉ接口"), 0.0f);
    }

    @Test
    void shouldRejectNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new HashingEmbeddingProvider(0));
        assertThrows(IllegalArgumentException.class, () -> new HashingEmbeddingProvider(-1));
    }
}
