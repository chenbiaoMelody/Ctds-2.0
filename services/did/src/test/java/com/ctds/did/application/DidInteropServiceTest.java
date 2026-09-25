package com.ctds.did.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.InteropDirection;
import com.ctds.did.domain.InteropLog;
import com.ctds.did.domain.InteropLogRepository;
import com.ctds.did.domain.VerificationOutcome;
import com.ctds.did.domain.VerificationReason;
import com.ctds.std.StdDomain;
import com.ctds.std.StdDomainStatus;
import com.ctds.std.did.DidInteropSamples;
import com.ctds.std.did.DidInteropStandardApi;
import com.ctds.std.did.InteropClaim;
import com.ctds.std.did.InteropReason;
import com.ctds.std.did.InteropResult;
import com.ctds.std.did.InteropVerification;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 互认应用服务单元（WBS-3.1.10 hifi §6 边界表 §7 T5/T6 单元侧）：
 * 输入类拒绝不留痕、结论映射逐值、通道异常收敛为内部错误（S 型，不冒充业务结论）。
 */
class DidInteropServiceTest {

    private static final String PEER_SPACE = "linjiang";
    private static final String PEER_DID = "did:linjiang:peer-0001";
    private static final String LOCAL_DID = "did:ctds:local-0001";
    private static final String DATA = base64("原文");
    private static final String SIGNATURE = base64("签名");

    private final RecordingLogRepository logs = new RecordingLogRepository();

    @Test
    void 来访入参非法一律1005C0004且不留痕() {
        final DidInteropService service = service(StubInterop.passing());

        assertInputInvalid(() -> service.verifyInbound(" ", PEER_DID, DATA, SIGNATURE));
        assertInputInvalid(() -> service.verifyInbound(PEER_SPACE, "  ", DATA, SIGNATURE));
        assertInputInvalid(() -> service.verifyInbound(PEER_SPACE, PEER_DID, "", SIGNATURE));
        assertInputInvalid(() -> service.verifyInbound(PEER_SPACE, PEER_DID, "不是Base64!!", SIGNATURE));
        assertInputInvalid(() -> service.verifyInbound(PEER_SPACE, PEER_DID, DATA, "不是Base64!!"));
        assertInputInvalid(() -> service.verifyInbound(PEER_SPACE, PEER_DID, oversizedData(), SIGNATURE));
        assertInputInvalid(() -> service.verifyInbound(null, PEER_DID, DATA, SIGNATURE));

        assertThat(logs.inserted).as("输入类拒绝不留痕").isEmpty();
    }

    @Test
    void 出向DID格式非法返回1005C0004且不留痕() {
        final DidInteropService service = service(StubInterop.passing());

        assertInputInvalid(() -> service.verifyOutbound("peer-0001"));
        assertInputInvalid(() -> service.verifyOutbound(" "));

        assertThat(logs.inserted).isEmpty();
    }

    @Test
    void 来访结论落留痕四要素() {
        final DidInteropService service = service(
                StubInterop.passing().inbound(new InteropVerification(PEER_SPACE, PEER_DID, InteropResult.PASS, null)));

        final DidInteropService.InteropVerificationResult result =
                service.verifyInbound(PEER_SPACE, PEER_DID, DATA, SIGNATURE);

        assertThat(result.result()).isEqualTo(VerificationOutcome.PASS);
        assertThat(result.reason()).isNull();
        assertThat(result.peerSpace()).isEqualTo(PEER_SPACE);
        assertThat(result.verifiedAt()).isNotNull();

        assertThat(logs.inserted).hasSize(1);
        final InteropLog log = logs.inserted.get(0);
        assertThat(log.direction()).isEqualTo(InteropDirection.INBOUND);
        assertThat(log.peerSpace()).isEqualTo(PEER_SPACE);
        assertThat(log.did()).isEqualTo(PEER_DID);
        assertThat(log.result()).isEqualTo(VerificationOutcome.PASS);
        assertThat(log.reason()).isNull();
        assertThat(log.occurredAt()).isEqualTo(result.verifiedAt());
    }

    @Test
    void 出向结论按出向方向留痕() {
        final DidInteropService service = service(StubInterop.passing()
                .outbound(new InteropVerification(PEER_SPACE, LOCAL_DID, InteropResult.FAIL, InteropReason.REVOKED)));

        final DidInteropService.InteropVerificationResult result = service.verifyOutbound(LOCAL_DID);

        assertThat(result.result()).isEqualTo(VerificationOutcome.FAIL);
        assertThat(result.reason()).isEqualTo(VerificationReason.REVOKED);
        assertThat(logs.inserted).hasSize(1);
        assertThat(logs.inserted.get(0).direction()).isEqualTo(InteropDirection.OUTBOUND);
        assertThat(logs.inserted.get(0).did()).isEqualTo(LOCAL_DID);
    }

    @Test
    void 互认结论原因逐值映射到验证口径() {
        for (final InteropReason reason : InteropReason.values()) {
            logs.inserted.clear();
            final DidInteropService service = service(StubInterop.passing()
                    .inbound(new InteropVerification(PEER_SPACE, PEER_DID, InteropResult.FAIL, reason)));

            final DidInteropService.InteropVerificationResult result =
                    service.verifyInbound(PEER_SPACE, PEER_DID, DATA, SIGNATURE);

            assertThat(result.reason().name()).as("原因 %s 逐字映射", reason).isEqualTo(reason.name());
        }

        logs.inserted.clear();
        final DidInteropService unavailable = service(StubInterop.passing().inbound(
                new InteropVerification(PEER_SPACE, PEER_DID, InteropResult.UNAVAILABLE,
                        InteropReason.BINDING_UNAVAILABLE)));
        assertThat(unavailable.verifyInbound(PEER_SPACE, PEER_DID, DATA, SIGNATURE).result())
                .isEqualTo(VerificationOutcome.UNAVAILABLE);
    }

    @Test
    void 互认通道异常收敛为内部错误且不留痕() {
        final DidInteropService service = service(
                StubInterop.failingInbound(new IllegalStateException("样例清单加载失败")));

        assertThatThrownBy(() -> service.verifyInbound(PEER_SPACE, PEER_DID, DATA, SIGNATURE))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode())
                        .isEqualTo(DidErrorCodes.DID_VERIFICATION_INTERNAL_ERROR));
        assertThat(logs.inserted).as("内部错误不留痕").isEmpty();
    }

    @Test
    void 样例清单读取异常收敛为内部错误() {
        final DidInteropService service = service(StubInterop.failingSamples());

        assertThatThrownBy(service::samples)
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode())
                        .isEqualTo(DidErrorCodes.DID_VERIFICATION_INTERNAL_ERROR));
    }

    private DidInteropService service(final StubInterop interop) {
        return new DidInteropService(interop, logs);
    }

    private static void assertInputInvalid(final ThrowingCallable executable) {
        assertThatThrownBy(executable)
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode())
                        .isEqualTo(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID));
    }

    private static String oversizedData() {
        return Base64.getEncoder().encodeToString(new byte[1024 * 1024 + 1]);
    }

    private static String base64(final String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 记录型留痕仓储桩（不留痕 = 列表为空）。 */
    private static final class RecordingLogRepository implements InteropLogRepository {

        private final List<InteropLog> inserted = new ArrayList<>();

        @Override
        public void insert(final InteropLog log) {
            inserted.add(log);
        }
    }

    /** 互认域桩（可配置结论/异常）。 */
    private static final class StubInterop implements DidInteropStandardApi {

        private Supplier<InteropVerification> inbound = () -> null;
        private Supplier<InteropVerification> outbound = () -> null;
        private Supplier<DidInteropSamples> samples = () -> null;

        static StubInterop passing() {
            return new StubInterop();
        }

        static StubInterop failingInbound(final RuntimeException failure) {
            final StubInterop stub = new StubInterop();
            stub.inbound = () -> {
                throw failure;
            };
            return stub;
        }

        static StubInterop failingSamples() {
            final StubInterop stub = new StubInterop();
            stub.samples = () -> {
                throw new IllegalStateException("样例清单不可读");
            };
            return stub;
        }

        StubInterop inbound(final InteropVerification verification) {
            this.inbound = () -> verification;
            return this;
        }

        StubInterop outbound(final InteropVerification verification) {
            this.outbound = () -> verification;
            return this;
        }

        @Override
        public StdDomain domain() {
            return StdDomain.DID_INTEROP;
        }

        @Override
        public StdDomainStatus status() {
            return new StdDomainStatus(StdDomain.DID_INTEROP, true, "已开放");
        }

        @Override
        public InteropVerification verifyInbound(final InteropClaim claim) {
            return inbound.get();
        }

        @Override
        public InteropVerification verifyOutbound(final String did) {
            return outbound.get();
        }

        @Override
        public DidInteropSamples samples() {
            return samples.get();
        }
    }
}
