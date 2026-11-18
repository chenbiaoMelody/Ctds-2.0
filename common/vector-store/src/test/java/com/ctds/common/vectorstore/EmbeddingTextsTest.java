package com.ctds.common.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmbeddingTextsTest {

    @Test
    void canonicalizeShouldNormalizeWidthCaseAndWhitespace() {
        assertEquals("abc 金融 风控", EmbeddingTexts.canonicalize("  ＡＢＣ\t金融\n\n风控  "));
        assertEquals("", EmbeddingTexts.canonicalize(null));
        assertEquals("", EmbeddingTexts.canonicalize("   "));
    }

    @Test
    void sourceHashShouldBeStableSha256Hex() {
        final String hash = EmbeddingTexts.sourceHash("金融风控");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, EmbeddingTexts.sourceHash("金融风控"));
        assertNotEquals(hash, EmbeddingTexts.sourceHash("金融风控模型"));
    }
}
