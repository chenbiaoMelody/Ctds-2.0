package com.ctds.std;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorType;
import org.junit.jupiter.api.Test;

/**
 * H5：1003 码表契约（码值/类型位/统一文案）与快速失败边界（hifi 错误码契约/边界表）。
 */
class StdAdapterErrorCodesTest {

    @Test
    void 错误码九位格式与C类型位() {
        assertThat(StdAdapterErrorCodes.NOT_IMPLEMENTED.value()).isEqualTo("1003C0001");
        assertThat(StdAdapterErrorCodes.NOT_IMPLEMENTED.type()).isEqualTo(ErrorType.CLIENT);
    }

    @Test
    void 未开放异常携带统一码与三域统一文案() {
        for (final StdDomain domain : StdDomain.values()) {
            final BizException ex = StdAdapterErrorCodes.notImplemented(domain);
            assertThat(ex.getErrorCode()).isEqualTo(StdAdapterErrorCodes.NOT_IMPLEMENTED);
            assertThat(ex.getMessage()).isEqualTo("该标准互联功能尚未开放");
        }
    }

    @Test
    void 空域参数快速失败() {
        assertThatThrownBy(() -> StdAdapterErrorCodes.notImplemented(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
