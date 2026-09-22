package com.ctds.kms.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.crypto.Sm2Service;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.kms.domain.KeyAuditRecord;
import com.ctds.kms.domain.KeyDescriptor;
import com.ctds.kms.domain.KeyPair;
import com.ctds.kms.domain.KeyRepository;
import com.ctds.kms.domain.KeyVersion;
import com.ctds.kms.domain.KmsErrorCodes;
import com.ctds.kms.domain.KmsKeys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SM2 密钥对托管应用服务单元测试（WBS-3.1.8 hifi §4.2）：私钥信封落库（库中无私钥明文）、
 * 内部签名验签往返、编号冲突/不存在/非法、非 SM2 拒绝签名。仓储用内存桩，密码学走真实 Sm2/Sm4。
 */
class KeyPairServiceTest {

    private static final byte[] TEST_ROOT_MATERIAL = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private FakeKeyPairRepository repository;
    private KeyPairService service;
    private Sm2Service sm2;
    private Sm4Service sm4;

    @BeforeEach
    void setUp() {
        repository = new FakeKeyPairRepository();
        sm2 = new Sm2Service();
        sm4 = new Sm4Service(keyRef -> {
            if (!KmsKeys.ROOT_KEY_REF.equals(keyRef)) {
                throw new BizException(com.ctds.common.crypto.CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE, "密钥服务暂不可用");
            }
            return TEST_ROOT_MATERIAL.clone();
        });
        service = new KeyPairService(repository, sm2, sm4);
    }

    @Test
    void createStoresPrivateKeyAsEnvelopeNotPlaintext() {
        final KeyPairService.KeyPairCreated created = service.create("did-key");
        assertThat(created.publicKeyHex()).hasSize(130).startsWith("04");

        // 落库的是根密钥信封（密文），不是 64 位小写 hex 的私钥明文（反向探针：删加密实现必变红）
        final String stored = repository.storedCipher("did-key");
        assertThat(stored).doesNotMatch("^[0-9a-f]{64}$");
        final byte[] decrypted = sm4.decrypt(Base64.getDecoder().decode(stored), KmsKeys.ROOT_KEY_REF);
        assertThat(decrypted).hasSize(32);
    }

    @Test
    void signThenVerifyRoundtrip() {
        final KeyPairService.KeyPairCreated created = service.create("did-key");
        final byte[] data = "验签原文".getBytes(StandardCharsets.UTF_8);

        final byte[] signature = service.sign("did-key", data);

        assertThat(sm2.verify(data, signature, created.publicKeyHex())).isTrue();
        // 签名不返回私钥：signature 与私钥 D 值无关（结构上 KeyPairService 无任何私钥出站方法）
    }

    @Test
    void duplicateKeyRefIsRejected() {
        service.create("did-key");
        assertThatThrownBy(() -> service.create("did-key"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(KmsErrorCodes.KMS_KEY_ALREADY_EXISTS));
    }

    @Test
    void unknownKeyRefIsNotFoundOnSign() {
        assertThatThrownBy(() -> service.sign("nope", "data".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(KmsErrorCodes.KMS_KEY_NOT_FOUND));
    }

    @Test
    void invalidKeyRefIsInputInvalid() {
        assertThatThrownBy(() -> service.create("bad ref!"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(KmsErrorCodes.KMS_INPUT_INVALID));
        assertThatThrownBy(() -> service.create(null))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(KmsErrorCodes.KMS_INPUT_INVALID));
    }

    @Test
    void emptyDataIsInputInvalidOnSign() {
        service.create("did-key");
        assertThatThrownBy(() -> service.sign("did-key", new byte[0]))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(KmsErrorCodes.KMS_INPUT_INVALID));
    }

    @Test
    void nonSm2KeyRejectedForSigning() {
        repository.seedSm4("sm4-key");
        assertThatThrownBy(() -> service.sign("sm4-key", "data".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(KmsErrorCodes.KMS_INPUT_INVALID));
    }

    /** 内存仓储桩：仅承载 SM2 密钥对语义，其余 SM4 方法不涉及。 */
    private static final class FakeKeyPairRepository implements KeyRepository {
        private final Map<String, KeyPair> pairs = new HashMap<>();

        String storedCipher(final String keyRef) {
            return pairs.get(keyRef).privateCipher();
        }

        void seedSm4(final String keyRef) {
            pairs.put(keyRef, new KeyPair(keyRef, "SM4", null, "cipher", LocalDateTime.now()));
        }

        @Override
        public void create(final KeyDescriptor descriptor, final KeyVersion versionOne, final KeyAuditRecord audit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rotate(final String keyRef, final int newVersion, final KeyVersion newVersionRecord,
                           final KeyAuditRecord audit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendAudit(final KeyAuditRecord audit) {
        }

        @Override
        public boolean exists(final String keyRef) {
            return pairs.containsKey(keyRef);
        }

        @Override
        public Optional<KeyDescriptor> findDescriptor(final String keyRef) {
            return Optional.empty();
        }

        @Override
        public Optional<KeyVersion> findVersion(final String keyRef, final int version) {
            return Optional.empty();
        }

        @Override
        public void createKeyPair(final KeyPair keyPair, final KeyAuditRecord audit) {
            pairs.put(keyPair.keyRef(), keyPair);
        }

        @Override
        public Optional<KeyPair> findKeyPair(final String keyRef) {
            return Optional.ofNullable(pairs.get(keyRef));
        }

        @Override
        public Optional<String> findKeyType(final String keyRef) {
            final KeyPair pair = pairs.get(keyRef);
            return pair == null ? Optional.empty() : Optional.of(pair.keyType());
        }
    }
}
