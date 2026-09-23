package com.ctds.did.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidStatus;
import com.ctds.did.support.DidRepositoryStub;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DID 解析单元测试（WBS-3.1.9 行为 2 B1~B4 + 标识格式边界）：
 * 有效/已吊销照常返回文档与状态（状态仅两值）、未登记明确答复、非法标识拒绝、文档仅公开要素。
 */
class DidResolutionServiceTest {

    private static final String SUBJECT_NO = "S20260922000001";
    private static final String DID = "did:ctds:S20260922000001.1";
    private static final String PUBLIC_KEY_HEX = "04" + "ab".repeat(64);
    private static final String DOCUMENT = "{\"did\":\"" + DID + "\",\"publicKey\":{\"type\":\"SM2\","
            + "\"algorithm\":\"sm2p256v1\",\"valueHex\":\"" + PUBLIC_KEY_HEX + "\"},"
            + "\"controller\":\"" + SUBJECT_NO + "\",\"service\":[{\"id\":\"#resolution\","
            + "\"type\":\"DidResolution\",\"serviceEndpoint\":\"/api/v1/did\"}],"
            + "\"created\":\"2026-09-22T20:45:17\"}";

    private DidRepositoryStub repository;
    private DidResolutionService service;

    @BeforeEach
    void setUp() {
        repository = new DidRepositoryStub();
        service = new DidResolutionService(repository, new ObjectMapper());
    }

    @Test
    void resolveActiveReturnsDocumentAndStatus() {
        repository.put(identity(DidStatus.ACTIVE));

        final DidResolutionService.ResolutionResult result = service.resolve(DID);

        assertThat(result.did()).isEqualTo(DID);
        assertThat(result.status()).isEqualTo(DidStatus.ACTIVE);
        // B1/B4：文档字段集严格 = 公开要素五键（不含私钥/密钥引用/任何 L4 字段）
        assertThat(result.document().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("did", "publicKey", "controller", "service", "created");
        assertThat(result.document().at("/publicKey/valueHex").asText()).isEqualTo(PUBLIC_KEY_HEX);
        assertThat(result.document().has("keyRef")).isFalse();
        assertThat(result.document().has("privateKey")).isFalse();
    }

    @Test
    void resolveRevokedStillReturnsDocumentWithRevokedStatus() {
        repository.put(identity(DidStatus.REVOKED));

        final DidResolutionService.ResolutionResult result = service.resolve(DID);

        assertThat(result.status()).isEqualTo(DidStatus.REVOKED);
        assertThat(result.document().get("did").asText()).isEqualTo(DID);
    }

    @Test
    void resolveUnknownDidReturnsNotRegisteredAnswer() {
        assertThatThrownBy(() -> service.resolve("did:ctds:S20260922009999.1"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_NOT_REGISTERED));
    }

    @Test
    void resolveInvalidDidFormatIsRejected() {
        assertThatThrownBy(() -> service.resolve(null))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_PARAM_INVALID));
        assertThatThrownBy(() -> service.resolve("  "))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_PARAM_INVALID));
        assertThatThrownBy(() -> service.resolve("ctds:S20260922000001.1"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_PARAM_INVALID));
    }

    private static DidIdentity identity(final DidStatus status) {
        return new DidIdentity(1L, SUBJECT_NO, 1, DID, status, PUBLIC_KEY_HEX, "did-" + SUBJECT_NO + "-1",
                DOCUMENT, status == DidStatus.REVOKED ? null : SUBJECT_NO,
                LocalDateTime.of(2026, 9, 22, 20, 45, 17), LocalDateTime.of(2026, 9, 22, 20, 45, 17));
    }
}