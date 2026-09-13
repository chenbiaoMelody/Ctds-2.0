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
}
