package com.ctds.kms.domain;

import java.util.Optional;

/** 密钥库仓储端口（infrastructure 实现；domain 不依赖存储细节）。 */
public interface KeyRepository {

    /** 新建密钥：描述符 + 版本 1 材料 + 审计同事务落库；编号已存在 → KMS_KEY_ALREADY_EXISTS。 */
    void create(KeyDescriptor descriptor, KeyVersion versionOne, KeyAuditRecord audit);

    /** 轮换：新增版本并把描述符指向新版本、写审计，同事务。 */
    void rotate(String keyRef, int newVersion, KeyVersion newVersionRecord, KeyAuditRecord audit);

    void appendAudit(KeyAuditRecord audit);

    boolean exists(String keyRef);

    Optional<KeyDescriptor> findDescriptor(String keyRef);

    Optional<KeyVersion> findVersion(String keyRef, int version);

    /** 新建 SM2 密钥对（WBS-3.1.8）：kms_key（key_type + 公钥）+ kms_key_version v1（私钥 D 值信封）同事务。 */
    void createKeyPair(KeyPair keyPair, KeyAuditRecord audit);

    /** 按编号取 SM2 密钥对（含私钥 D 值信封，仅供签名时解密）。 */
    Optional<KeyPair> findKeyPair(String keyRef);
}
