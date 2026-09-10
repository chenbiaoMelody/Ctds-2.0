package com.ctds.example.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.std.StdDomain;
import com.ctds.std.did.PlaceholderDidInteropStandardApi;
import com.ctds.std.evidence.PlaceholderEvidenceStandardApi;
import com.ctds.std.interconnect.PlaceholderInterconnectStandardApi;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * H6 排序解耦契约（评审④P2-1）：服务输出固定按枚举声明序（互联互通→DID 互认→测评证据），
 * 与容器 Bean 注入顺序解耦——乱序注入仍须升序，钉死"删除 .sorted 也全绿"的存活变异。
 */
class StdCapabilityServiceTest {

    @Test
    void 乱序注入的探活输出仍按枚举声明序() {
        final StdCapabilityService service = new StdCapabilityService(List.of(
                new PlaceholderEvidenceStandardApi(),
                new PlaceholderInterconnectStandardApi(),
                new PlaceholderDidInteropStandardApi()));
        assertThat(service.statuses())
                .extracting(status -> status.domain())
                .containsExactly(StdDomain.INTERCONNECT, StdDomain.DID_INTEROP, StdDomain.EVIDENCE);
    }
}
