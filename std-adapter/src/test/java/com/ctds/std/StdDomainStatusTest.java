package com.ctds.std;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * H2：域枚举契约与状态记录构造校验（WBS 2.4.8 hifi 类型清单/边界表）。
 */
class StdDomainStatusTest {

    @Test
    void 三标准域固定且契约字段完整() {
        assertThat(StdDomain.values()).hasSize(3);
        assertThat(StdDomain.INTERCONNECT.code()).isEqualTo("interconnect");
        assertThat(StdDomain.DID_INTEROP.code()).isEqualTo("did");
        assertThat(StdDomain.EVIDENCE.code()).isEqualTo("evidence");
        for (final StdDomain domain : StdDomain.values()) {
            assertThat(domain.displayName()).isNotBlank();
        }
    }

    @Test
    void 状态记录拒绝空域与空文案() {
        assertThatThrownBy(() -> new StdDomainStatus(null, false, "文案"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StdDomainStatus(StdDomain.INTERCONNECT, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StdDomainStatus(StdDomain.INTERCONNECT, false, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 合法状态构造并保留全部字段() {
        final StdDomainStatus status = new StdDomainStatus(StdDomain.EVIDENCE, false, "尚未开放");
        assertThat(status.domain()).isEqualTo(StdDomain.EVIDENCE);
        assertThat(status.implemented()).isFalse();
        assertThat(status.message()).isEqualTo("尚未开放");
    }
}
