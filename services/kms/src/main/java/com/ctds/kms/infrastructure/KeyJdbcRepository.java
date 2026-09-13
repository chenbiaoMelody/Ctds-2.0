package com.ctds.kms.infrastructure;

import com.ctds.common.errorcode.BizException;
import com.ctds.kms.domain.KeyAuditRecord;
import com.ctds.kms.domain.KeyDescriptor;
import com.ctds.kms.domain.KeyRepository;
import com.ctds.kms.domain.KeyStatus;
import com.ctds.kms.domain.KeyVersion;
import com.ctds.kms.domain.KmsErrorCodes;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL 密钥库仓储（JdbcClient，ADR-009 迁移规范建表）。
 * 创建/轮换同事务落库（描述符 + 版本 + 审计），保证"审计四要素与版本变更要么都有要么都没有"。
 */
@Repository
public class KeyJdbcRepository implements KeyRepository {

    private final JdbcClient jdbc;

    public KeyJdbcRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void create(final KeyDescriptor descriptor, final KeyVersion versionOne, final KeyAuditRecord audit) {
        try {
            jdbc.sql("INSERT INTO kms_key (key_ref, status, current_version, created_at) VALUES (?, ?, ?, ?)")
                    .params(descriptor.keyRef(), descriptor.status().name(),
                            descriptor.currentVersion(), Timestamp.valueOf(descriptor.createdAt()))
                    .update();
        } catch (final DuplicateKeyException e) {
            throw new BizException(KmsErrorCodes.KMS_KEY_ALREADY_EXISTS, "密钥编号已存在");
        }
        insertVersion(versionOne);
        appendAudit(audit);
    }

    @Override
    @Transactional
    public void rotate(final String keyRef, final int newVersion, final KeyVersion newVersionRecord,
                       final KeyAuditRecord audit) {
        insertVersion(newVersionRecord);
        final int updated = jdbc.sql("UPDATE kms_key SET current_version = ? WHERE key_ref = ? AND current_version < ?")
                .params(newVersion, keyRef, newVersion)
                .update();
        if (updated == 0) {
            throw new BizException(KmsErrorCodes.KMS_KEY_NOT_FOUND, "密钥编号不存在");
        }
        appendAudit(audit);
    }

    @Override
    public void appendAudit(final KeyAuditRecord audit) {
        jdbc.sql("INSERT INTO kms_key_audit (action, key_ref, old_version, new_version, operator, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")
                .params(audit.action(), audit.keyRef(), audit.oldVersion(), audit.newVersion(),
                        audit.operator(), Timestamp.valueOf(audit.occurredAt()))
                .update();
    }

    @Override
    public boolean exists(final String keyRef) {
        return jdbc.sql("SELECT COUNT(1) FROM kms_key WHERE key_ref = ?")
                .param(keyRef)
                .query(Integer.class)
                .single() > 0;
    }

    @Override
    public Optional<KeyDescriptor> findDescriptor(final String keyRef) {
        return jdbc.sql("SELECT key_ref, status, current_version, created_at FROM kms_key WHERE key_ref = ?")
                .param(keyRef)
                .query((rs, rowNum) -> new KeyDescriptor(
                        rs.getString("key_ref"),
                        KeyStatus.valueOf(rs.getString("status")),
                        rs.getInt("current_version"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .optional();
    }

    @Override
    public Optional<KeyVersion> findVersion(final String keyRef, final int version) {
        return jdbc.sql("SELECT key_ref, version, material_cipher, created_at FROM kms_key_version "
                        + "WHERE key_ref = ? AND version = ?")
                .params(keyRef, version)
                .query((rs, rowNum) -> new KeyVersion(
                        rs.getString("key_ref"),
                        rs.getInt("version"),
                        rs.getString("material_cipher"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .optional();
    }

    private void insertVersion(final KeyVersion record) {
        try {
            jdbc.sql("INSERT INTO kms_key_version (key_ref, version, material_cipher, created_at) "
                            + "VALUES (?, ?, ?, ?)")
                    .params(record.keyRef(), record.version(), record.materialCipher(),
                            Timestamp.valueOf(record.createdAt()))
                    .update();
        } catch (final DuplicateKeyException e) {
            // 材料表版本冲突 = 描述符与版本不一致（并发轮换重复提交）→ 收敛编号已存在口径之外的业务错误
            throw new BizException(KmsErrorCodes.KMS_INTERNAL_ERROR, "密钥版本冲突，请重试");
        }
    }
}
