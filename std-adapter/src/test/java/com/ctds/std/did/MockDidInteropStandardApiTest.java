package com.ctds.std.did;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.std.StdDomain;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 模拟对端互认实现（WBS-3.1.10 hifi §7 T1~T4/T6/T7 单元侧 + §6 边界表 + T10 收口守卫）：
 * 来访三查判定顺序（签名 → 对端状态 → 对端绑定）、未登记/空间不匹配、出向回放、通道不可用不冒充"不通过"。
 */
class MockDidInteropStandardApiTest {

    private static final String PEER_SPACE = "linjiang";

    private final DidInteropSamples samples = DidInteropSamples.fromClasspath();

    @Test
    void 有效样例来访三查全过() {
        final InteropSample sample = sample(InteropScenario.VALID);

        final InteropVerification verification = inboundMock().verifyInbound(claimOf(sample));

        assertThat(verification.result()).isEqualTo(InteropResult.PASS);
        assertThat(verification.reason()).isNull();
        assertThat(verification.peerSpace()).isEqualTo(PEER_SPACE);
        assertThat(verification.did()).isEqualTo(sample.did());
    }

    @Test
    void 对端已吊销样例状态核验失败() {
        final InteropVerification verification =
                inboundMock().verifyInbound(claimOf(sample(InteropScenario.PEER_REVOKED)));

        assertThat(verification.result()).isEqualTo(InteropResult.FAIL);
        assertThat(verification.reason()).isEqualTo(InteropReason.REVOKED);
    }

    @Test
    void 签名被篡改样例签名核验失败() {
        final InteropVerification verification = inboundMock().verifyInbound(claimOf(sample(InteropScenario.TAMPERED)));

        assertThat(verification.result()).isEqualTo(InteropResult.FAIL);
        assertThat(verification.reason()).isEqualTo(InteropReason.SIGNATURE_INVALID);
    }

    @Test
    void 未预置DID的来访不成立() {
        final InteropVerification verification = inboundMock()
                .verifyInbound(new InteropClaim(PEER_SPACE, "did:linjiang:peer-9999",
                        bytes("任意原文"), bytes("任意签名")));

        assertThat(verification.result()).isEqualTo(InteropResult.FAIL);
        assertThat(verification.reason()).isEqualTo(InteropReason.NOT_REGISTERED);
    }

    @Test
    void 对端空间标识不匹配的来访不成立() {
        final InteropSample sample = sample(InteropScenario.VALID);
        final InteropClaim claim = new InteropClaim("unknown-space", sample.did(),
                Base64.getDecoder().decode(sample.data()), Base64.getDecoder().decode(sample.signature()));

        final InteropVerification verification = inboundMock().verifyInbound(claim);

        assertThat(verification.reason()).isEqualTo(InteropReason.NOT_REGISTERED);
    }

    @Test
    void 对端绑定失效的来访不成立() {
        final InteropSample bound = sample(InteropScenario.VALID);
        final InteropSample bindingLost = new InteropSample(bound.sampleId(), bound.did(), InteropScenario.VALID,
                bound.data(), bound.signature(), bound.publicKeyHex(), false, true, InteropResult.FAIL,
                InteropReason.SUBJECT_BINDING_FAILED);
        final DidInteropSamples custom = new DidInteropSamples(PEER_SPACE, "临江数据空间", List.of(bindingLost));

        final InteropVerification verification =
                new MockDidInteropStandardApi(custom).verifyInbound(claimOf(bindingLost));

        assertThat(verification.result()).isEqualTo(InteropResult.FAIL);
        assertThat(verification.reason()).isEqualTo(InteropReason.SUBJECT_BINDING_FAILED);
    }

    @Test
    void 出向验证回放本空间状态三态() {
        assertThat(outboundMock(Optional.of(new LocalDidStatus(true, true))).verifyOutbound(localDid()).result())
                .isEqualTo(InteropResult.PASS);
        assertThat(outboundMock(Optional.of(new LocalDidStatus(true, false))).verifyOutbound(localDid()).reason())
                .isEqualTo(InteropReason.REVOKED);
        assertThat(outboundMock(Optional.of(new LocalDidStatus(false, false))).verifyOutbound(localDid()).reason())
                .isEqualTo(InteropReason.NOT_REGISTERED);
    }

    @Test
    void 出向验证端口异常返回不可用而非不通过() {
        final LocalDidStatusPort failing = did -> {
            throw new IllegalStateException("端口不可达");
        };

        final InteropVerification verification =
                new MockDidInteropStandardApi(samples, failing).verifyOutbound(localDid());

        assertThat(verification.result()).isEqualTo(InteropResult.UNAVAILABLE);
        assertThat(verification.reason()).isEqualTo(InteropReason.BINDING_UNAVAILABLE);
        assertThat(verification.peerSpace()).isEqualTo(PEER_SPACE);
    }

    @Test
    void 无本空间身份端口时出向返回不可用() {
        final InteropVerification verification = new MockDidInteropStandardApi(samples).verifyOutbound(localDid());

        assertThat(verification.result()).isEqualTo(InteropResult.UNAVAILABLE);
        assertThat(verification.reason()).isEqualTo(InteropReason.BINDING_UNAVAILABLE);
    }

    @Test
    void 样例清单出口与预置清单一致() {
        final DidInteropSamples exposed = new MockDidInteropStandardApi(samples).samples();

        assertThat(exposed.samples()).hasSize(3);
        assertThat(exposed.peerSpaceName()).isEqualTo("临江数据空间");
    }

    @Test
    void DID互认域已开放且占位类已删除() {
        final DidInteropStandardApi api = new MockDidInteropStandardApi(samples);

        assertThat(api.domain()).isEqualTo(StdDomain.DID_INTEROP);
        assertThat(api.status().implemented()).isTrue();
        assertThat(api.status().message()).isNotBlank();
        assertThatThrownBy(() -> Class.forName("com.ctds.std.did.PlaceholderDidInteropStandardApi"))
                .as("占位类必须已删除（替换规则：删除而非并存）")
                .isInstanceOf(ClassNotFoundException.class);
    }

    private MockDidInteropStandardApi inboundMock() {
        return new MockDidInteropStandardApi(samples);
    }

    private MockDidInteropStandardApi outboundMock(final Optional<LocalDidStatus> status) {
        final LocalDidStatus value = status.orElse(null);
        return new MockDidInteropStandardApi(samples, did -> value);
    }

    private InteropSample sample(final InteropScenario scenario) {
        return samples.samples().stream()
                .filter(sample -> sample.scenario() == scenario)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少样例：" + scenario));
    }

    private static InteropClaim claimOf(final InteropSample sample) {
        return new InteropClaim(PEER_SPACE, sample.did(),
                Base64.getDecoder().decode(sample.data()), Base64.getDecoder().decode(sample.signature()));
    }

    private static String localDid() {
        return "did:ctds:local-0001";
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
