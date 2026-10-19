package com.ctds.delivery.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.delivery.domain.DeliveryErrorCodes;
import org.junit.jupiter.api.Test;

/** 交付错误码表与出站处理器集合一致性（防止码表↔处理器漂移）。 */
class DeliveryExceptionHandlerTest {

    @Test
    void mappedCodesCoverAllDeliveryErrorCodes() {
        assertThat(DeliveryExceptionHandler.MAPPED_CODES).containsExactlyInAnyOrder(
                DeliveryErrorCodes.REQUESTER_NOT_ALLOWED.value(),
                DeliveryErrorCodes.DELIVERY_PARAM_INVALID.value(),
                DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH.value(),
                DeliveryErrorCodes.DELIVERY_NOT_VISIBLE.value(),
                DeliveryErrorCodes.FILE_TOO_LARGE.value(),
                DeliveryErrorCodes.PACKAGE_NOT_ALLOWED.value(),
                DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE.value(),
                DeliveryErrorCodes.PACKAGE_OPERATION_FAILED.value());
    }
}
