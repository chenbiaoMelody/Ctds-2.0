package com.ctds.std.did;

import com.ctds.common.crypto.Sm2Service;
import com.ctds.std.StdDomain;
import com.ctds.std.StdDomainStatus;
import java.util.Optional;

/**
 * 模拟对端互认实现（WBS-3.1.10 W1/W2/W5；规格 C-1.2 §7 Q3=A 模拟对端）：按预置样例给出结论。
 * <p>来访三查（对端口径，不查本空间主体库）：① 签名核验 = 样例公钥真实 SM2 验签；② 对端状态核验；
 * ③ 对端绑定核验；全过 = 通过，任一不满足 = 不通过并给出明确原因。</p>
 * <p>出向验证经 {@link LocalDidStatusPort} 读取本空间状态后回放；端口异常、端口缺省或返回未知 →
 * {@code UNAVAILABLE}（系统态诚实表征，<b>不冒充"不通过"</b>）。</p>
 * <p><b>诚实边界</b>：演示期模拟对端 + 业务口径方法，非信通院协议实现（ADR-008 变更补记登记）。</p>
 */
public class MockDidInteropStandardApi implements DidInteropStandardApi {

    private static final String OPEN_MESSAGE = "跨空间身份互认能力已开放（演示期模拟对端，业务口径方法）";

    private final DidInteropSamples samples;
    private final LocalDidStatusPort localDidStatusPort;
    private final Sm2Service sm2Service;

    /** 无本空间身份能力的使用方（如演示壳服务）：出向验证一律返回不可用。 */
    public MockDidInteropStandardApi(final DidInteropSamples samples) {
        this(samples, null);
    }

    public MockDidInteropStandardApi(final DidInteropSamples samples, final LocalDidStatusPort localDidStatusPort) {
        this.samples = samples;
        this.localDidStatusPort = localDidStatusPort;
        this.sm2Service = new Sm2Service();
    }

    @Override
    public StdDomain domain() {
        return StdDomain.DID_INTEROP;
    }

    @Override
    public StdDomainStatus status() {
        return new StdDomainStatus(StdDomain.DID_INTEROP, true, OPEN_MESSAGE);
    }

    @Override
    public InteropVerification verifyInbound(final InteropClaim claim) {
        final Optional<InteropSample> found = match(claim);
        if (found.isEmpty()) {
            return new InteropVerification(claim.peerSpace(), claim.did(), InteropResult.FAIL,
                    InteropReason.NOT_REGISTERED);
        }
        final InteropSample sample = found.get();
        if (!signatureValid(sample, claim)) {
            return conclude(claim, InteropResult.FAIL, InteropReason.SIGNATURE_INVALID);
        }
        if (sample.peerRevoked()) {
            return conclude(claim, InteropResult.FAIL, InteropReason.REVOKED);
        }
        if (sample.peerBindingLost()) {
            return conclude(claim, InteropResult.FAIL, InteropReason.SUBJECT_BINDING_FAILED);
        }
        return conclude(claim, InteropResult.PASS, null);
    }

    @Override
    public InteropVerification verifyOutbound(final String did) {
        if (localDidStatusPort == null) {
            return unavailable(did);
        }
        final LocalDidStatus status;
        try {
            status = localDidStatusPort.statusOf(did);
        } catch (final RuntimeException e) {
            // 通道取数异常（端口不可达/委托异常）→ 系统态诚实表征，不冒充业务结论
            return unavailable(did);
        }
        if (status == null) {
            return unavailable(did);
        }
        if (!status.registered()) {
            return new InteropVerification(samples.peerSpace(), did, InteropResult.FAIL, InteropReason.NOT_REGISTERED);
        }
        if (!status.effective()) {
            return new InteropVerification(samples.peerSpace(), did, InteropResult.FAIL, InteropReason.REVOKED);
        }
        return new InteropVerification(samples.peerSpace(), did, InteropResult.PASS, null);
    }

    @Override
    public DidInteropSamples samples() {
        return samples;
    }

    private Optional<InteropSample> match(final InteropClaim claim) {
        if (claim.peerSpace() == null || !claim.peerSpace().equals(samples.peerSpace())) {
            return Optional.empty();
        }
        return samples.samples().stream()
                .filter(sample -> sample.did().equals(claim.did()))
                .findFirst();
    }

    private boolean signatureValid(final InteropSample sample, final InteropClaim claim) {
        return sm2Service.verify(claim.data(), claim.signature(), sample.publicKeyHex());
    }

    private InteropVerification unavailable(final String did) {
        return new InteropVerification(samples.peerSpace(), did, InteropResult.UNAVAILABLE,
                InteropReason.BINDING_UNAVAILABLE);
    }

    private static InteropVerification conclude(final InteropClaim claim, final InteropResult result,
            final InteropReason reason) {
        return new InteropVerification(claim.peerSpace(), claim.did(), result, reason);
    }
}
