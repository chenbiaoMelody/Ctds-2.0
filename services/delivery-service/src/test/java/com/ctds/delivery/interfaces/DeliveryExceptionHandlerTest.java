package com.ctds.delivery.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.ctds.delivery.domain.SandboxErrorCodes;
import org.junit.jupiter.api.Test;

/** 交付错误码表与出站处理器集合一致性（防止码表↔处理器漂移；WBS-3.5.4 增沙箱码值；
 * WBS-3.5.5 增结果物码值）。 */
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
                DeliveryErrorCodes.PACKAGE_OPERATION_FAILED.value(),
                SandboxErrorCodes.SANDBOX_NOT_VISIBLE.value(),
                SandboxErrorCodes.SANDBOX_STATE_CONFLICT.value(),
                SandboxErrorCodes.SANDBOX_DATA_DENIED.value(),
                SandboxErrorCodes.SANDBOX_QUOTA_EXCEEDED.value(),
                SandboxErrorCodes.SANDBOX_JOB_NOT_ALLOWED.value(),
                SandboxErrorCodes.SANDBOX_RESULT_NOT_VISIBLE.value(),
                SandboxErrorCodes.SANDBOX_RESULT_STATE_CONFLICT.value(),
                SandboxErrorCodes.SANDBOX_RUNTIME_UNAVAILABLE.value(),
                SandboxErrorCodes.SANDBOX_OPERATION_FAILED.value());
    }

    @Test
    void sandboxCodesMapToDeclaredHttpStatus() {
        assertThat(DeliveryExceptionHandler.statusOf("1009C0010").value()).isEqualTo(404);
        assertThat(DeliveryExceptionHandler.statusOf("1009C0011").value()).isEqualTo(409);
        assertThat(DeliveryExceptionHandler.statusOf("1009C0012").value()).isEqualTo(403);
        assertThat(DeliveryExceptionHandler.statusOf("1009C0013").value()).isEqualTo(429);
        assertThat(DeliveryExceptionHandler.statusOf("1009C0014").value()).isEqualTo(400);
        assertThat(DeliveryExceptionHandler.statusOf("1009C0015").value()).isEqualTo(404);
        assertThat(DeliveryExceptionHandler.statusOf("1009C0016").value()).isEqualTo(409);
        assertThat(DeliveryExceptionHandler.statusOf("1009S0004").value()).isEqualTo(503);
        assertThat(DeliveryExceptionHandler.statusOf("1009S0005").value()).isEqualTo(500);
    }
}
