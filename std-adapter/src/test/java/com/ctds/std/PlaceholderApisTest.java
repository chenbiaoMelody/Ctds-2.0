package com.ctds.std;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.std.evidence.EvidenceStandardApi;
import com.ctds.std.evidence.PlaceholderEvidenceStandardApi;
import com.ctds.std.interconnect.InterconnectStandardApi;
import com.ctds.std.interconnect.PlaceholderInterconnectStandardApi;
import org.junit.jupiter.api.Test;

/**
 * H4：占位域实现逐一断言域映射、implemented=false 与定稿文案（hifi"占位实现状态值"表）。
 * <p>注：DID 互认域占位类已由 WBS-3.1.10 按替换规则删除（真实实现 = {@code MockDidInteropStandardApi}，
 * 守卫与断言见 {@code com.ctds.std.did.MockDidInteropStandardApiTest}）。</p>
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
    void 测评证据域占位返回未开放状态与定稿文案() {
        final EvidenceStandardApi api = new PlaceholderEvidenceStandardApi();
        assertThat(api.domain()).isEqualTo(StdDomain.EVIDENCE);
        assertThat(api.status().domain()).isEqualTo(StdDomain.EVIDENCE);
        assertThat(api.status().implemented()).isFalse();
        assertThat(api.status().message())
                .isEqualTo("该能力域尚未开放：测评证据采集与互联互通契约测试将由测评演练工作包实现（M2）");
    }
}
