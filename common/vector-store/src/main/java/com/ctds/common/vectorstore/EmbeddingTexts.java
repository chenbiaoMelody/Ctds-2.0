package com.ctds.common.vectorstore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;

/**
 * 文本规范化与源指纹（C-11.2 B1/B2）：规范化口径唯一来源；源指纹仅用于缓存变更检测，
 * 非安全用途（不涉及密钥与加解密）。
 */
public final class EmbeddingTexts {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private EmbeddingTexts() {
    }

    /** NFKC + 空白折叠 + ASCII 小写 + trim；null → 空串。 */
    public static String canonicalize(final String text) {
        if (text == null) {
            return "";
        }
        final String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        final StringBuilder out = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        int index = 0;
        while (index < normalized.length()) {
            final int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            if (codePoint >= 'A' && codePoint <= 'Z') {
                out.append((char) (codePoint + ('a' - 'A')));
            } else {
                out.appendCodePoint(codePoint);
            }
        }
        return out.toString();
    }

    /** SHA-256 小写十六进制（64 字符）；仅用于索引源文本变更检测。 */
    public static String sourceHash(final String canonicalText) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest(canonicalText == null
                    ? new byte[0] : canonicalText.getBytes(StandardCharsets.UTF_8));
            final char[] hex = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                final int value = bytes[i] & 0xff;
                hex[i * 2] = HEX[value >>> 4];
                hex[i * 2 + 1] = HEX[value & 0x0f];
            }
            return new String(hex);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
