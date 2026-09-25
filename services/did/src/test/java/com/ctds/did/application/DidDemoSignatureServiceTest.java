package com.ctds.did.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCode;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidKmsClient;
import com.ctds.did.domain.DidStatus;
import com.ctds.did.support.DidRepositoryStub;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 演示签名入口单元测试（WBS-3.1.11，hifi T8/T9；规格 C-1.2 §6 第 6 条边界）：
 * 入口默认关闭（1000C0003 且零 KMS 调用，关闭态不区分目标存在性）；原文边界；未登记；
 * 未完成签发（无密钥引用）；KMS 不可用 → 1005S0002。无任何库表写入路径（演示通道不留痕）。
 */
class DidDemoSignatureServiceTest {

    private static final String SUBJECT_NO = "S20260925100301";
    private static final String DID = "did:ctds:" + SUBJECT_NO + ".1";
    private static final String KEY_REF = "did-" + SUBJECT_NO + "-1";
    private static final String PUBLIC_KEY_HEX = "04" + "ab".repeat(64);
    private static final String PLAINTEXT = "蓝天数据科技有限公司确认接入城市可信数据空间";

    private final DidRepositoryStub repository = new DidRepositoryStub();
    private final StubKmsClient kmsClient = new StubKmsClient();

    @Test
    void disabledEntryReturnsResourceNotFoundWithoutKmsCall() {
        // T8：出厂/生产默认关闭 → 复用既有 1000C0003；且不产生任何 KMS 调用
        putIdentity(KEY_REF);
        final DidDemoSignatureService service = service(false);

        assertError(() -> service.sign(DID, PLAINTEXT), ErrorCodes.RESOURCE_NOT_FOUND,
                "演示签名入口未启用（仅演示/调试期）");
        assertThat(kmsClient.signCalls).isZero();
    }

    @Test
    void disabledEntryDoesNotRevealWhetherDidExists() {
        // T8 边界：关闭态下"未登记 DID"与"已登记 DID"返回同一错误与文案（不构成状态枚举通道）
        final DidDemoSignatureService service = service(false);
        final ErrorCode unknown = errorCodeOf(() -> service.sign("did:ctds:S20260925999999.1", PLAINTEXT));
        putIdentity(KEY_REF);
        final ErrorCode known = errorCodeOf(() -> service.sign(DID, PLAINTEXT));

        assertThat(known).isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND);
        assertThat(unknown).isEqualTo(ErrorCodes.RESOURCE_NOT_FOUND);
        assertThat(kmsClient.signCalls).isZero();
    }

    @Test
    void enabledEntryReturnsBase64PlaintextAndSignatureFromKms() {
        putIdentity(KEY_REF);
        final DidDemoSignatureService service = service(true);

        final DidDemoSignatureService.DemoSignatureResult result = service.sign(DID, PLAINTEXT);

        assertThat(result.did()).isEqualTo(DID);
        assertThat(result.data()).isEqualTo(Base64.getEncoder()
                .encodeToString(PLAINTEXT.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.signature()).isEqualTo("c2ln");
        assertThat(result.signedAt()).isNotNull();
        assertThat(kmsClient.signCalls).isEqualTo(1);
        assertThat(kmsClient.lastDataBase64).isEqualTo(result.data());
    }

    @Test
    void emptyOrOverlongPlaintextRejectedWithParamInvalid() {
        putIdentity(KEY_REF);
        final DidDemoSignatureService service = service(true);

        assertError(() -> service.sign(DID, null), DidErrorCodes.DID_PARAM_INVALID, "待签内容不能为空");
        assertError(() -> service.sign(DID, "   "), DidErrorCodes.DID_PARAM_INVALID, "待签内容不能为空");
        assertError(() -> service.sign(DID, "字".repeat(1025)), DidErrorCodes.DID_PARAM_INVALID,
                "待签内容长度不能超过1024字符");
        assertThat(kmsClient.signCalls).isZero();
    }

    @Test
    void unknownDidReturnsNotRegistered() {
        final DidDemoSignatureService service = service(true);

        assertError(() -> service.sign("did:ctds:S20260925999998.1", PLAINTEXT),
                DidErrorCodes.DID_NOT_REGISTERED, "该 DID 未登记");
        assertThat(kmsClient.signCalls).isZero();
    }

    @Test
    void identityWithoutKeyRefReturnsNoPendingIssuance() {
        // T9：记录未完成签发（无密钥引用）→ 复用 1005B0001，文案明确；不调用 KMS
        putIdentity(null);
        final DidDemoSignatureService service = service(true);

        assertError(() -> service.sign(DID, PLAINTEXT), DidErrorCodes.DID_NO_PENDING_ISSUANCE,
                "该主体 DID 尚未完成签发，无法生成演示签名");
        assertThat(kmsClient.signCalls).isZero();
    }

    @Test
    void kmsFailureMapsToInternalErrorWithGenericMessage() {
        // T9：KMS 不可用 → 1005S0002 + 通用文案（不暴露内部实现）
        putIdentity(KEY_REF);
        kmsClient.failNext();
        final DidDemoSignatureService service = service(true);

        assertError(() -> service.sign(DID, PLAINTEXT), DidErrorCodes.DID_VERIFICATION_INTERNAL_ERROR,
                "签名服务暂不可用，请稍后重试");
        assertThat(kmsClient.signCalls).isEqualTo(1);
    }

    private DidDemoSignatureService service(final boolean enabled) {
        return new DidDemoSignatureService(repository, kmsClient, enabled);
    }

    private void putIdentity(final String keyRef) {
        final LocalDateTime now = LocalDateTime.now().withNano(0);
        repository.put(new DidIdentity(1L, SUBJECT_NO, 1, DID, DidStatus.ACTIVE, PUBLIC_KEY_HEX, keyRef,
                "{\"did\":\"" + DID + "\"}", SUBJECT_NO, now, now));
    }

    private static void assertError(final Runnable action, final ErrorCode expected, final String message) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(expected);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }

    private static ErrorCode errorCodeOf(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("预期抛出 BizException");
        } catch (final BizException e) {
            return e.getErrorCode();
        }
    }

    /** KMS 桩：记录调用次数与入参；failNext 后下一次签名抛异常（模拟不可用）。 */
    private static final class StubKmsClient implements DidKmsClient {

        private int signCalls;
        private String lastDataBase64;
        private boolean failOnce;

        void failNext() {
            failOnce = true;
        }

        @Override
        public String createKeyPair(final String keyRef) {
            throw new UnsupportedOperationException("演示签名测试未使用");
        }

        @Override
        public String sign(final String keyRef, final String dataBase64) {
            signCalls++;
            lastDataBase64 = dataBase64;
            if (failOnce) {
                failOnce = false;
                throw new IllegalStateException("KMS 不可达");
            }
            return "c2ln";
        }
    }
}
