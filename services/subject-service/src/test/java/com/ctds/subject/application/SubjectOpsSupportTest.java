package com.ctds.subject.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 文件名归一化单测（WBS-3.1.5 hifi B8①，3.1.4 观察项处置）：控制字符剥除 / 首尾空白去除 /
 * 超长截断三例 + null 透传（透传后由既有文件校验拒绝）。
 */
class SubjectOpsSupportTest {

    private final SubjectOpsSupport ops = new SubjectOpsSupport(null);

    @Test
    void stripsControlCharactersFromFileName() {
        assertThat(ops.normalizeFileName("A1\u0001\u0007.jpg")).isEqualTo("A1.jpg");
        assertThat(ops.normalizeFileName("证照\u001F影像.jpg")).isEqualTo("证照影像.jpg");
        assertThat(ops.normalizeFileName("cert\u007Ffile.cer")).isEqualTo("certfile.cer");
    }

    @Test
    void trimsOuterWhitespaceAndKeepsInnerSpaces() {
        assertThat(ops.normalizeFileName("  a1 .jpg ")).isEqualTo("a1 .jpg");
        assertThat(ops.normalizeFileName("\tA3.cer\n")).isEqualTo("A3.cer");
    }

    @Test
    void truncatesToMaxLength() {
        final String overlong = "长".repeat(300) + ".jpg";
        final String normalized = ops.normalizeFileName(overlong);
        assertThat(normalized).hasSize(255);
        assertThat(ops.normalizeFileName("short.jpg")).isEqualTo("short.jpg");
    }

    @Test
    void nullAndEmptyPassThroughForDownstreamValidation() {
        assertThat(ops.normalizeFileName(null)).isNull();
        assertThat(ops.normalizeFileName("   ")).isEmpty();
    }
}
