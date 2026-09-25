package com.ctds.std.did;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.crypto.Sm2Service;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 预置样例清单（WBS-3.1.10 hifi §7 T8 单元侧 + §6 样例边界）：三态齐全、签名为制作期真实 SM2 签名、
 * 公钥编码口径（130 hex 非压缩点）、样例资源不含任何私钥材料（红线 7 反向探针）。
 */
class DidInteropSamplesTest {

    /** 独立 64 位 hex 私钥样式（前后非 hex 边界，区别于 130 位公钥内的 64 位片段）。 */
    private static final Pattern PRIVATE_KEY_STYLE =
            Pattern.compile("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])");

    @Test
    void 资源加载出对端空间标识与三态样例() {
        final DidInteropSamples samples = DidInteropSamples.fromClasspath();
        assertThat(samples.peerSpace()).isEqualTo("linjiang");
        assertThat(samples.peerSpaceName()).isEqualTo("临江数据空间");
        assertThat(samples.samples()).extracting(InteropSample::scenario)
                .containsExactlyInAnyOrder(InteropScenario.VALID, InteropScenario.PEER_REVOKED,
                        InteropScenario.TAMPERED);
    }

    @Test
    void 样例签名真实且验签结果与预期结论一致() {
        final Sm2Service sm2 = new Sm2Service();
        for (final InteropSample sample : DidInteropSamples.fromClasspath().samples()) {
            final boolean verified = sm2.verify(Base64.getDecoder().decode(sample.data()),
                    Base64.getDecoder().decode(sample.signature()), sample.publicKeyHex());
            assertThat(verified).as("样例 %s 验签结果", sample.sampleId())
                    .isEqualTo(sample.expectedReason() != InteropReason.SIGNATURE_INVALID);
        }
    }

    @Test
    void 样例公钥为130位十六进制非压缩点() {
        for (final InteropSample sample : DidInteropSamples.fromClasspath().samples()) {
            assertThat(sample.publicKeyHex()).as("样例 %s 公钥", sample.sampleId())
                    .hasSize(130).startsWith("04").matches("[0-9a-f]{130}");
            assertThat(sample.data()).isNotBlank();
            assertThat(sample.signature()).isNotBlank();
        }
    }

    @Test
    void 样例资源不含私钥材料() throws Exception {
        final String raw;
        try (InputStream in = DidInteropSamplesTest.class.getResourceAsStream("/std/did-interop-samples.json")) {
            assertThat(in).as("样例资源存在").isNotNull();
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(raw).doesNotContain("privateKey", "PrivateKey", "PRIVATE KEY");
        assertThat(PRIVATE_KEY_STYLE.matcher(raw).find()).as("资源中不得出现 64 位 hex 私钥样式").isFalse();
    }
}
