package com.ctds.kms.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.kms.domain.KeyAuditRecord;
import com.ctds.kms.domain.KeyDescriptor;
import com.ctds.kms.domain.KeyRepository;
import com.ctds.kms.domain.KeyStatus;
import com.ctds.kms.domain.KeyVersion;
import com.ctds.kms.domain.KmsErrorCodes;
import com.ctds.kms.domain.KmsKeys;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 密钥管理应用服务（WBS-2.6.3）：创建（生成 16 字节随机材料为 v1）、轮换（新版本 + 审计四要素）、
 * 材料供给（当前/历史版本，供 common-crypto KmsKeyProvider 调用）。
 * 材料加密/解密一律经 common-crypto 统一入口（根密钥信封），本服务不接触算法（红线：不自研密码学）。
 * 日志只记编号/动作结果，不记材料与信封内容（红线：密钥材料禁入日志）。
 */
@Service
public class KeyManagementService {

    private static final Logger log = LoggerFactory.getLogger(KeyManagementService.class);
    private static final int SM4_KEY_BYTES = 16;
    private static final int MAX_KEY_REF_CHARS = 64;
    private static final String KEY_REF_PATTERN = "[A-Za-z0-9._-]+";
    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_ROTATE = "ROTATE";

    private final KeyRepository repository;
    private final Sm4Service sm4Service;
    private final SecureRandom random = new SecureRandom();

    public KeyManagementService(final KeyRepository repository, final Sm4Service sm4Service) {
        this.repository = repository;
        this.sm4Service = sm4Service;
    }

    /** 创建密钥：生成随机材料为版本 1；重复编号 → 1002B0001。 */
    public KeyDescriptor create(final String keyRef) {
        requireKeyRef(keyRef);
        if (repository.exists(keyRef)) {
            throw new BizException(KmsErrorCodes.KMS_KEY_ALREADY_EXISTS, "密钥编号已存在");
        }
        final LocalDateTime now = LocalDateTime.now();
        final KeyDescriptor descriptor = new KeyDescriptor(keyRef, KeyStatus.ENABLED, 1, now);
        final byte[] material = new byte[SM4_KEY_BYTES];
        random.nextBytes(material);
        final KeyVersion versionOne =
                new KeyVersion(keyRef, 1, encryptMaterial(material), now);
        repository.create(descriptor, versionOne,
                new KeyAuditRecord(ACTION_CREATE, keyRef, null, 1, operator(), now));
        log.info("KMS key created: keyRef={} version=1", keyRef);
        return descriptor;
    }

    /** 轮换：新增版本（当前 + 1），旧版本保留供解密旧密文（规格行为 2：旧密文不失效）。 */
    public KeyDescriptor rotate(final String keyRef) {
        requireKeyRef(keyRef);
        final KeyDescriptor descriptor = repository.findDescriptor(keyRef)
                .orElseThrow(() -> new BizException(KmsErrorCodes.KMS_KEY_NOT_FOUND, "密钥编号不存在"));
        final LocalDateTime now = LocalDateTime.now();
        final int oldVersion = descriptor.currentVersion();
        final int newVersion = oldVersion + 1;
        final byte[] material = new byte[SM4_KEY_BYTES];
        random.nextBytes(material);
        final KeyVersion versionRecord =
                new KeyVersion(keyRef, newVersion, encryptMaterial(material), now);
        repository.rotate(keyRef, newVersion, versionRecord,
                new KeyAuditRecord(ACTION_ROTATE, keyRef, oldVersion, newVersion, operator(), now));
        log.info("KMS key rotated: keyRef={} versions {}->{}", keyRef, oldVersion, newVersion);
        return new KeyDescriptor(keyRef, descriptor.status(), newVersion, descriptor.createdAt());
    }

    /** 密钥元数据（无材料）。 */
    public KeyDescriptor descriptor(final String keyRef) {
        return repository.findDescriptor(requireKeyRef(keyRef))
                .orElseThrow(() -> new BizException(KmsErrorCodes.KMS_KEY_NOT_FOUND, "密钥编号不存在"));
    }

    /** 当前版本密钥材料（Base64 编码的 16 字节原文材料，给 KmsKeyProvider）。 */
    public KeyMaterial currentMaterial(final String keyRef) {
        final KeyDescriptor descriptor = descriptor(keyRef);
        return material(keyRef, descriptor.currentVersion());
    }

    /** 指定版本密钥材料（解密旧密文用；版本不存在 → 1002B0002）。 */
    public KeyMaterial material(final String keyRef, final int version) {
        final KeyVersion record = repository.findVersion(requireKeyRef(keyRef), version)
                .orElseThrow(() -> new BizException(KmsErrorCodes.KMS_KEY_NOT_FOUND, "密钥编号或版本不存在"));
        return new KeyMaterial(keyRef, record.version(), decryptMaterial(record.materialCipher()));
    }

    private String encryptMaterial(final byte[] material) {
        return Base64.getEncoder().encodeToString(sm4Service.encrypt(material, KmsKeys.ROOT_KEY_REF));
    }

    private byte[] decryptMaterial(final String cipherBase64) {
        return sm4Service.decrypt(Base64.getDecoder().decode(cipherBase64), KmsKeys.ROOT_KEY_REF);
    }

    private static String operator() {
        final String subject = AuthContext.subject();
        return subject == null ? "anonymous" : subject;
    }

    private static String requireKeyRef(final String keyRef) {
        if (keyRef == null || keyRef.isBlank() || keyRef.length() > MAX_KEY_REF_CHARS
                || !keyRef.matches(KEY_REF_PATTERN)) {
            throw new BizException(KmsErrorCodes.KMS_INPUT_INVALID, "密钥编号不合法（仅允许字母数字 . _ -）");
        }
        return keyRef;
    }

    /** 密钥材料供给视图（record：编号 + 版本 + 材料）。 */
    public record KeyMaterial(String keyRef, int version, byte[] material) {
    }
}
