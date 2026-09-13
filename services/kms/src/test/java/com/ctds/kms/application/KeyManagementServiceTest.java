package com.ctds.kms.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.kms.domain.KeyAuditRecord;
import com.ctds.kms.domain.KeyDescriptor;
import com.ctds.kms.domain.KeyRepository;
import com.ctds.kms.domain.KeyVersion;
import com.ctds.kms.domain.KmsErrorCodes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 密钥管理应用服务单元测试（规格 C-2.6.3 行为 1/2 全分支，仓储用内存桩）：
 * 创建/轮换/审计四要素/材料往返加密落库/重复与未知编号/非法编号。
 */
class KeyManagementServiceTest {

    private static final byte[] TEST_ROOT_MATERIAL = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private FakeKeyRepository repository;
    private KeyManagementService service;
    private Sm4Service sm4;

    @BeforeEach
    void setUp() {
        repository = new FakeKeyRepository();
        sm4 = new Sm4Service(keyRef -> {
            if (!com.ctds.kms.domain.KmsKeys.ROOT_KEY_REF.equals(keyRef)) {
                throw new BizException(com.ctds.common.crypto.CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE, "密钥服务暂不可用");
            }
            return TEST_ROOT_MATERIAL.clone();
        });
        service = new KeyManagementService(repository, sm4);
    }

    @AfterEach
    void clearIdentity() {
        AuthContext.clear();
    }

    @Test
    void createGeneratesRandomMaterialAsVersionOne() {
        final KeyDescriptor descriptor = service.create("order-data");
        assertThat(descriptor.currentVersion()).isEqualTo(1);
        assertThat(descriptor.status().name()).isEqualTo("ENABLED");
        final KeyManagementService.KeyMaterial material = service.currentMaterial("order-data");
        assertThat(material.version()).isEqualTo(1);
        assertThat(material.material()).hasSize(16);
        // 两把密钥材料互不相同（随机生成生效）
        service.create("other-data");
        assertThat(service.currentMaterial("order-data").material())
                .isNotEqualTo(service.currentMaterial("other-data").material());
    }

    @Test
    void storedMaterialIsCiphertextNotPlaintext() {
        // 落库的是根密钥信封（密文），不是材料本身（规格行为 1：库中无明文密钥）
        service.create("order-data");
        final String stored = repository.versions.get("order-data|1");
        assertThat(stored).isNotEqualTo(Base64.getEncoder()
                .encodeToString(service.currentMaterial("order-data").material()));
        // 且密文可由同一根密钥解回（信封格式生效）
        final byte[] decrypted = sm4.decrypt(Base64.getDecoder().decode(stored),
                com.ctds.kms.domain.KmsKeys.ROOT_KEY_REF);
        assertThat(decrypted).isEqualTo(service.currentMaterial("order-data").material());
    }

    @Test
    void rotateAddsVersionAndWritesAuditFourElements() {
        // AuthContext.set 为组件包内接缝，单测不伪造身份（操作者四要素由 KmsKeysIntegrationTest 真实头部验证）
        service.create("order-data");
        final byte[] materialV1 = service.currentMaterial("order-data").material();

        final KeyDescriptor after = service.rotate("order-data");

        assertThat(after.currentVersion()).isEqualTo(2);
        assertThat(service.currentMaterial("order-data").version()).isEqualTo(2);
        assertThat(service.currentMaterial("order-data").material()).isNotEqualTo(materialV1);
        // 旧版本材料保留（规格行为 2：旧密文不失效的前提）
        assertThat(service.material("order-data", 1).material()).isEqualTo(materialV1);

        final List<KeyAuditRecord> audits = repository.audits;
        final KeyAuditRecord createAudit = audits.get(0);
        assertThat(createAudit.action()).isEqualTo("CREATE");
        final KeyAuditRecord rotateAudit = audits.get(audits.size() - 1);
        assertThat(rotateAudit.action()).isEqualTo("ROTATE");
        assertThat(rotateAudit.keyRef()).isEqualTo("order-data");
        assertThat(rotateAudit.oldVersion()).isEqualTo(1);
        assertThat(rotateAudit.newVersion()).isEqualTo(2);
        assertThat(rotateAudit.operator()).isEqualTo("anonymous");
        assertThat(rotateAudit.occurredAt()).isNotNull();
    }

    @Test
    void duplicateKeyRefIsRejected() {
        service.create("order-data");
        assertThatThrownBy(() -> service.create("order-data"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(KmsErrorCodes.KMS_KEY_ALREADY_EXISTS));
    }

    @Test
    void unknownKeyRefIsNotFound() {
        assertThatThrownBy(() -> service.rotate("nope"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(KmsErrorCodes.KMS_KEY_NOT_FOUND));
        assertThatThrownBy(() -> service.currentMaterial("nope"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(KmsErrorCodes.KMS_KEY_NOT_FOUND));
        assertThatThrownBy(() -> service.material("nope", 3))
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
        assertThatThrownBy(() -> service.create("x".repeat(65)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(KmsErrorCodes.KMS_INPUT_INVALID));
    }

    /** 内存仓储桩：记录版本材料与审计，语义与 KeyJdbcRepository 一致。 */
    private static final class FakeKeyRepository implements KeyRepository {
        private final Map<String, KeyDescriptor> descriptors = new HashMap<>();
        private final Map<String, String> versions = new HashMap<>();
        private final List<KeyAuditRecord> audits = new ArrayList<>();

        @Override
        public void create(final KeyDescriptor descriptor, final KeyVersion versionOne,
                           final KeyAuditRecord audit) {
            if (descriptors.putIfAbsent(descriptor.keyRef(), descriptor) != null) {
                throw new BizException(KmsErrorCodes.KMS_KEY_ALREADY_EXISTS, "密钥编号已存在");
            }
            versions.put(descriptor.keyRef() + "|" + versionOne.version(), versionOne.materialCipher());
            audits.add(audit);
        }

        @Override
        public void rotate(final String keyRef, final int newVersion, final KeyVersion newVersionRecord,
                           final KeyAuditRecord audit) {
            final KeyDescriptor old = descriptors.get(keyRef);
            if (old == null) {
                throw new BizException(KmsErrorCodes.KMS_KEY_NOT_FOUND, "密钥编号不存在");
            }
            versions.put(keyRef + "|" + newVersionRecord.version(), newVersionRecord.materialCipher());
            descriptors.put(keyRef, new KeyDescriptor(keyRef, old.status(), newVersion, old.createdAt()));
            audits.add(audit);
        }

        @Override
        public void appendAudit(final KeyAuditRecord audit) {
            audits.add(audit);
        }

        @Override
        public boolean exists(final String keyRef) {
            return descriptors.containsKey(keyRef);
        }

        @Override
        public Optional<KeyDescriptor> findDescriptor(final String keyRef) {
            return Optional.ofNullable(descriptors.get(keyRef));
        }

        @Override
        public Optional<KeyVersion> findVersion(final String keyRef, final int version) {
            final String cipher = versions.get(keyRef + "|" + version);
            return cipher == null ? Optional.empty()
                    : Optional.of(new KeyVersion(keyRef, version, cipher, java.time.LocalDateTime.now()));
        }
    }
}
