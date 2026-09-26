package com.ctds.space.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.errorcode.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * 1006 段错误码格式单测（WBS-3.2.3 hifi §2 / T14 值域必填格；12 枚举封闭性由
 * SpaceDomainEnumsTest（WBS-3.2.2）覆盖，本类补码值登记面）：
 * 9 位 = 段 4 位 + 类型 1 位（C/S）+ 序号 4 位；段位锁死 1006（规格 Q7 预留，本卡定稿）。
 */
class SpaceErrorCodesFormatTest {

    @Test
    void admissionRequiredIsClientTypeInSpaceSegment() {
        assertCode("1006C0001", SpaceErrorCodes.ADMISSION_REQUIRED, 'C');
    }

    @Test
    void statusGateIsClientTypeInSpaceSegment() {
        assertCode("1006C0002", SpaceErrorCodes.SPACE_STATUS_GATE, 'C');
    }

    @Test
    void nameTakenIsClientTypeInSpaceSegment() {
        assertCode("1006C0003", SpaceErrorCodes.SPACE_NAME_TAKEN, 'C');
    }

    @Test
    void notFoundIsClientTypeInSpaceSegment() {
        assertCode("1006C0004", SpaceErrorCodes.SPACE_NOT_FOUND, 'C');
    }

    @Test
    void elementMissingIsClientTypeInSpaceSegment() {
        assertCode("1006C0005", SpaceErrorCodes.SPACE_ELEMENT_MISSING, 'C');
    }

    @Test
    void dissolveConfirmRequiredIsClientTypeInSpaceSegment() {
        assertCode("1006C0006", SpaceErrorCodes.DISSOLVE_CONFIRM_REQUIRED, 'C');
    }

    @Test
    void accessDeniedIsClientTypeInSpaceSegment() {
        assertCode("1006C0007", SpaceErrorCodes.SPACE_ACCESS_DENIED, 'C');
    }

    @Test
    void subjectServiceUnavailableIsSystemTypeInSpaceSegment() {
        assertCode("1006S0001", SpaceErrorCodes.SUBJECT_SERVICE_UNAVAILABLE, 'S');
    }

    @Test
    void codeTableIsExactlyTheEightFinalizedValues() {
        // 码表封闭性：1006 段定稿 8 码（hifi §2），增删须同步设计变更
        assertThat(new String[] {
                SpaceErrorCodes.ADMISSION_REQUIRED.value(),
                SpaceErrorCodes.SPACE_STATUS_GATE.value(),
                SpaceErrorCodes.SPACE_NAME_TAKEN.value(),
                SpaceErrorCodes.SPACE_NOT_FOUND.value(),
                SpaceErrorCodes.SPACE_ELEMENT_MISSING.value(),
                SpaceErrorCodes.DISSOLVE_CONFIRM_REQUIRED.value(),
                SpaceErrorCodes.SPACE_ACCESS_DENIED.value(),
                SpaceErrorCodes.SUBJECT_SERVICE_UNAVAILABLE.value()})
                .containsExactly("1006C0001", "1006C0002", "1006C0003", "1006C0004",
                        "1006C0005", "1006C0006", "1006C0007", "1006S0001");
    }

    private void assertCode(final String expected, final ErrorCode actual, final char type) {
        assertThat(actual.value()).as("9 位格式与定稿码值").isEqualTo(expected);
        assertThat(actual.type().marker()).as("类型位").isEqualTo(type);
    }
}
