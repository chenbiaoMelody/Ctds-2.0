package com.ctds.std;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.std.did.DidInteropStandardApi;
import com.ctds.std.did.PlaceholderDidInteropStandardApi;
import com.ctds.std.evidence.EvidenceStandardApi;
import com.ctds.std.evidence.PlaceholderEvidenceStandardApi;
import com.ctds.std.interconnect.InterconnectStandardApi;
import com.ctds.std.interconnect.PlaceholderInterconnectStandardApi;
import org.junit.jupiter.api.Test;

/**
 * H4：三域占位实现逐一断言域映射、implemented=false 与定稿文案（hifi"占位实现状态值"表）。
 */
class PlaceholderApisTest {

    @Test
    void 互联互通域占位返回未开放状态与定稿文案() {
        final InterconnectStandardApi api = new PlaceholderInterconnectStandardApi();
        assertThat(api.domain()).isEqualTo(StdDomain.INTERCONNECT);
        assertThat(api.status().domain()).isEqualTo(StdDomain.INTERCONNECT);
        assertThat(api.status().implemented()).isFalse();
        assertThat(api.status().message())
                .isEqualTo("该能力域尚未开放：区域枢纽对接与产品互挂接口将按信通院互联互通规范由后续工作包实现（WBS 4.x）");
    }

    @Test
    void DID互认域占位返回未开放状态与定稿文案() {
        final DidInteropStandardApi api = new PlaceholderDidInteropStandardApi();
        assertThat(api.domain()).isEqualTo(StdDomain.DID_INTEROP);
        assertThat(api.status().domain()).isEqualTo(StdDomain.DID_INTEROP);
        assertThat(api.status().implemented()).isFalse();
        assertThat(api.status().message())
                .isEqualTo("该能力域尚未开放：政务 CA 接入与跨空间身份互认接口将由后续工作包实现（WBS 3.1.4 / 3.1.10）");
    }

    @Test
    void 测评证据域占位返回未开放状态与定稿文案() {
        final EvidenceStandardApi api = new PlaceholderEvidenceStandardApi();
        assertThat(api.domain()).isEqualTo(StdDomain.EVIDENCE);
        assertThat(api.status().domain()).isEqualTo(StdDomain.EVIDENCE);
        assertThat(api.status().implemented()).isFalse();
        assertThat(api.status().message())
                .isEqualTo("该能力域尚未开放：测评证据采集与互联互通契约测试将由测评演练工作包实现（M2）");
    }

    @Test
    void 占位探活重复调用返回一致状态() {
        final InterconnectStandardApi api = new PlaceholderInterconnectStandardApi();
        assertThat(api.status()).isEqualTo(api.status());
    }
}
