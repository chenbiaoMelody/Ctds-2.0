package com.ctds.delivery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.errorcode.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 交付错误码一致性（hifi §7.2）：9 位码格式、1009 段、无重复、文案非空。 */
class DeliveryErrorCodesTest {

    private static final List<ErrorCode> CODES = List.of(
            DeliveryErrorCodes.REQUESTER_NOT_ALLOWED,
            DeliveryErrorCodes.DELIVERY_PARAM_INVALID,
            DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH,
            DeliveryErrorCodes.DELIVERY_NOT_VISIBLE,
            DeliveryErrorCodes.FILE_TOO_LARGE,
            DeliveryErrorCodes.PACKAGE_NOT_ALLOWED,
            DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE,
            DeliveryErrorCodes.PACKAGE_OPERATION_FAILED);

    @Test
    void codesFollowNineDigitFormatAndDeliverySegment() {
        assertThat(CODES).allSatisfy(code -> {
            assertThat(code.value()).matches("^\\d{4}[CBS]\\d{4}$");
            assertThat(code.value()).startsWith("1009");
        });
        assertThat(CODES.stream().map(ErrorCode::value).distinct().count()).isEqualTo(CODES.size());
    }

    @Test
    void messagesAreServerConstants() {
        assertThat(List.of(
                DeliveryErrorCodes.REQUESTER_NOT_ALLOWED_MESSAGE,
                DeliveryErrorCodes.DELIVERY_PARAM_INVALID_MESSAGE,
                DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH_MESSAGE,
                DeliveryErrorCodes.DELIVERY_NOT_VISIBLE_MESSAGE,
                DeliveryErrorCodes.FILE_TOO_LARGE_MESSAGE,
                DeliveryErrorCodes.PACKAGE_NOT_ALLOWED_MESSAGE,
                DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE,
                DeliveryErrorCodes.PACKAGE_OPERATION_FAILED_MESSAGE))
                .allSatisfy(message -> assertThat(message).isNotBlank());
        // 与网关 1009C0003 同值同文（hifi §7.2 一致性锚）
        assertThat(DeliveryErrorCodes.REQUESTER_NOT_ALLOWED_MESSAGE)
                .isEqualTo("非合约需求方不可发起交付调用");
    }
}
