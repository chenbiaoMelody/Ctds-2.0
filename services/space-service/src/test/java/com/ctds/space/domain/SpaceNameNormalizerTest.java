package com.ctds.space.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 空间名称归一化单测（WBS-3.2.3 hifi §4 / T13，3.2.2 移交①：空白集显式定义含 Unicode 空白）：
 * 控制/格式字符去除、全角空格与 nbsp 并入空白集、首尾 trim、内部连续空白折叠为单个空格。
 */
class SpaceNameNormalizerTest {

    @Test
    void nullAndEmptyNormalizeToEmpty() {
        assertThat(SpaceNameNormalizer.normalize(null)).isEmpty();
        assertThat(SpaceNameNormalizer.normalize("")).isEmpty();
    }

    @Test
    void plainNamePassesThrough() {
        assertThat(SpaceNameNormalizer.normalize("普惠金融空间")).isEqualTo("普惠金融空间");
        assertThat(SpaceNameNormalizer.normalize("Medical-验证_01")).isEqualTo("Medical-验证_01");
    }

    @Test
    void ideographicSpaceU3000IsWhitespaceAndFolds() {
        // 全角空格（U+3000）：首尾 trim + 内部折叠为单个半角空格
        assertThat(SpaceNameNormalizer.normalize("\u3000普惠\u3000\u3000金融空间\u3000"))
                .isEqualTo("普惠 金融空间");
    }

    @Test
    void noBreakSpaceU00A0IsWhitespace() {
        // nbsp（U+00A0）：Character.isWhitespace 不计（非断行），显式并入空白集（hifi §4 ②）
        assertThat(SpaceNameNormalizer.normalize("\u00A0空间A\u00A0")).isEqualTo("空间A");
        assertThat(SpaceNameNormalizer.normalize("空间\u00A0\u00A0B")).isEqualTo("空间 B");
    }

    @Test
    void zeroWidthAndBomFormatCharsRemoved() {
        // 零宽 U+200B~U+200D 与 BOM U+FEFF 属 Cf 格式字符：整字去除，不产生空白
        assertThat(SpaceNameNormalizer.normalize("\u200B空\u200C间\u200D")).isEqualTo("空间");
        assertThat(SpaceNameNormalizer.normalize("\uFEFF普惠金融空间")).isEqualTo("普惠金融空间");
    }

    @Test
    void controlCharsRemoved() {
        // Cc 控制字符（制表/换行/铃）整字去除；与空白折叠叠加时不产生多余分隔
        assertThat(SpaceNameNormalizer.normalize("\t空间\n")).isEqualTo("空间");
        assertThat(SpaceNameNormalizer.normalize("空\u0007间")).isEqualTo("空间");
    }

    @Test
    void innerAsciiWhitespaceRunsCollapseToOneSpace() {
        assertThat(SpaceNameNormalizer.normalize("  空间   A  \n\t B \t"))
                .isEqualTo("空间 A B");
    }

    @Test
    void whitespaceOnlyNormalizesToEmpty() {
        assertThat(SpaceNameNormalizer.normalize("\u3000\u00A0 \t\u200B")).isEmpty();
    }

    @Test
    void normalizationIsIdempotent() {
        final String once = SpaceNameNormalizer.normalize("\u3000普惠\u200B金融 空间\u00A0");
        assertThat(SpaceNameNormalizer.normalize(once)).isEqualTo(once);
    }
}
