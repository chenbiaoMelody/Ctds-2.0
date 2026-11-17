package com.ctds.common.vectorstore;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 本地确定性向量化（C-11.2 B1；Q3-A）：中文单字/二元组 + ASCII 词元，CRC32 哈希技巧，
 * L2 归一化；零外呼、零密钥、跨实例可复现。已知边界：哈希桶碰撞，相似度为词元重叠近似。
 */
public final class HashingEmbeddingProvider implements EmbeddingProvider {

    public static final int DEFAULT_DIMENSIONS = 384;

    private final int dimensions;

    public HashingEmbeddingProvider() {
        this(DEFAULT_DIMENSIONS);
    }

    public HashingEmbeddingProvider(final int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions 必须为正数");
        }
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(final String text) {
        final String canonical = EmbeddingTexts.canonicalize(text);
        final float[] vector = new float[dimensions];
        int index = 0;
        while (index < canonical.length()) {
            final int codePoint = canonical.codePointAt(index);
            if (isHan(codePoint)) {
                final int runStart = index;
                while (index < canonical.length() && isHan(canonical.codePointAt(index))) {
                    index += Character.charCount(canonical.codePointAt(index));
                }
                addHanRun(canonical, runStart, index, vector);
            } else if (isAsciiAlnum(codePoint)) {
                final int runStart = index;
                while (index < canonical.length() && isAsciiAlnum(canonical.codePointAt(index))) {
                    index += Character.charCount(canonical.codePointAt(index));
                }
                addToken(canonical.substring(runStart, index), vector);
            } else {
                index += Character.charCount(codePoint);
            }
        }
        return VectorMath.normalize(vector);
    }

    private void addHanRun(final String text, final int start, final int end, final float[] vector) {
        int index = start;
        int previousStart = -1;
        while (index < end) {
            final int codePoint = text.codePointAt(index);
            final int next = index + Character.charCount(codePoint);
            addToken(text.substring(index, next), vector);
            if (previousStart >= 0) {
                addToken(text.substring(previousStart, next), vector);
            }
            previousStart = index;
            index = next;
        }
    }

    private void addToken(final String token, final float[] vector) {
        final CRC32 crc = new CRC32();
        crc.update(token.getBytes(StandardCharsets.UTF_8));
        final long hash = crc.getValue();
        final int bucket = (int) (hash % dimensions);
        final float sign = ((hash >>> 31) & 1L) == 0L ? 1.0f : -1.0f;
        vector[bucket] += sign;
    }

    private static boolean isHan(final int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static boolean isAsciiAlnum(final int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= '0' && codePoint <= '9');
    }
}
