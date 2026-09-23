package com.ctds.did.infrastructure;

import com.ctds.common.errorcode.BizException;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidOperationLog;
import com.ctds.did.domain.DidRepository;
import com.ctds.did.domain.DidStatus;
import com.ctds.did.domain.VerificationLog;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL DID 身份注册表仓储（JdbcClient，ADR-009 迁移规范建表）。
 * 幂等守卫在库表层（uk_guard 唯一键）+ 应用层（存在即返回）双保险（hifi B2）。
 */
@Repository
public class DidJdbcRepository implements DidRepository {

    private static final String IDENTITY_COLUMNS =
            "id, subject_no, issuance_seq, did, status, public_key_hex, key_ref, document_json, "
                    + "guard_key, created_at, updated_at";

    private final JdbcClient jdbc;

    public DidJdbcRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DidIdentity> findActiveOrPending(final String subjectNo) {
        return jdbc.sql("SELECT " + IDENTITY_COLUMNS + " FROM did_identity "
                        + "WHERE subject_no = ? AND guard_key IS NOT NULL")
                .param(subjectNo)
                .query(this::mapIdentity)
                .optional();
    }

    @Override
    public Optional<DidIdentity> findPending(final String subjectNo) {
        return jdbc.sql("SELECT " + IDENTITY_COLUMNS + " FROM did_identity "
                        + "WHERE subject_no = ? AND guard_key IS NOT NULL AND status = ?")
                .params(subjectNo, DidStatus.PENDING_ISSUE.name())
                .query(this::mapIdentity)
                .optional();
    }

    @Override
    public Optional<DidIdentity> findLatestRevoked(final String subjectNo) {
        return jdbc.sql("SELECT " + IDENTITY_COLUMNS + " FROM did_identity "
                        + "WHERE subject_no = ? AND guard_key IS NULL AND status = ? "
                        + "ORDER BY issuance_seq DESC LIMIT 1")
                .params(subjectNo, DidStatus.REVOKED.name())
                .query(this::mapIdentity)
                .optional();
    }

    @Override
    public Optional<DidIdentity> findByDid(final String did) {
        return jdbc.sql("SELECT " + IDENTITY_COLUMNS + " FROM did_identity WHERE did = ?")
                .param(did)
                .query(this::mapIdentity)
                .optional();
    }

    @Override
    public DidIdentity createPending(final String subjectNo, final int issuanceSeq, final LocalDateTime now) {
        try {
            jdbc.sql("INSERT INTO did_identity (subject_no, issuance_seq, did, status, public_key_hex, "
                            + "key_ref, document_json, guard_key, created_at, updated_at) "
                            + "VALUES (?, ?, NULL, ?, NULL, NULL, NULL, ?, ?, ?)")
                    .params(subjectNo, issuanceSeq, DidStatus.PENDING_ISSUE.name(), subjectNo,
                            Timestamp.valueOf(now), Timestamp.valueOf(now))
                    .update();
        } catch (final DuplicateKeyException e) {
            // 并发重复触发：uk_guard 唯一键拒绝 → 收敛为幂等返回既有非吊销行（hifi B2）
        }
        return findActiveOrPending(subjectNo)
                .orElseThrow(() -> new IllegalStateException("待签发记录创建失败: subjectNo=" + subjectNo));
    }

    @Override
    @Transactional
    public boolean completeIssuance(final long identityId, final String publicKeyHex, final String documentJson,
            final DidOperationLog operationLog) {
        // 带 status 乐观门槛（与 revoke 对称）：并发双签发恰一人成功，败者不落第二条 ISSUE 留痕（hifi §6）
        final int updated = jdbc.sql("UPDATE did_identity SET did = ?, status = ?, public_key_hex = ?, "
                        + "key_ref = ?, document_json = ?, updated_at = ? WHERE id = ? AND status = ?")
                .params(operationLog.did(), DidStatus.ACTIVE.name(), publicKeyHex, operationLog.keyRef(),
                        documentJson, Timestamp.valueOf(operationLog.occurredAt()), identityId,
                        DidStatus.PENDING_ISSUE.name())
                .update();
        if (updated == 0) {
            // 门槛拦截：行已被并发完成（ACTIVE → 幂等）或状态已变（REVOKED/不存在 → 内部错误）
            final String status = jdbc.sql("SELECT status FROM did_identity WHERE id = ?")
                    .param(identityId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (DidStatus.ACTIVE.name().equals(status)) {
                return false;
            }
            throw new BizException(DidErrorCodes.DID_ISSUANCE_INTERNAL_ERROR, "签发处理失败，请重试");
        }
        insertOperationLog(operationLog);
        return true;
    }

    @Override
    public int nextIssuanceSeq(final String subjectNo) {
        final Integer max = jdbc.sql("SELECT COALESCE(MAX(issuance_seq), 0) FROM did_identity WHERE subject_no = ?")
                .param(subjectNo)
                .query(Integer.class)
                .single();
        return max + 1;
    }

    @Override
    @Transactional
    public void revoke(final long identityId, final DidOperationLog operationLog) {
        // 带 status 乐观门槛：并发双吊销恰一人成功（败者 0 行 → 1005C0003，不落第二条 REVOKE 留痕）
        final int updated = jdbc.sql("UPDATE did_identity SET status = ?, guard_key = NULL, updated_at = ? "
                        + "WHERE id = ? AND status = ?")
                .params(DidStatus.REVOKED.name(), Timestamp.valueOf(operationLog.occurredAt()), identityId,
                        DidStatus.ACTIVE.name())
                .update();
        if (updated == 0) {
            throw new BizException(DidErrorCodes.DID_REVOKE_NOT_ACTIVE, "非有效 DID 不可吊销");
        }
        insertOperationLog(operationLog);
    }

    @Override
    public void insertVerificationLog(final VerificationLog log) {
        jdbc.sql("INSERT INTO did_verification_log (did, result, reason, occurred_at) VALUES (?, ?, ?, ?)")
                .params(log.did(), log.result().name(),
                        log.reason() == null ? null : log.reason().name(),
                        Timestamp.valueOf(log.occurredAt()))
                .update();
    }

    private void insertOperationLog(final DidOperationLog log) {
        jdbc.sql("INSERT INTO did_operation_log (did, subject_no, operation, operator, reason, key_ref, "
                        + "status_from, status_to, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(log.did(), log.subjectNo(), log.operation(), log.operator(), log.reason(),
                        log.keyRef(), log.statusFrom(), log.statusTo(), Timestamp.valueOf(log.occurredAt()))
                .update();
    }

    private DidIdentity mapIdentity(final ResultSet rs, final int rowNum) throws SQLException {
        return new DidIdentity(
                rs.getLong("id"),
                rs.getString("subject_no"),
                rs.getInt("issuance_seq"),
                rs.getString("did"),
                DidStatus.valueOf(rs.getString("status")),
                rs.getString("public_key_hex"),
                rs.getString("key_ref"),
                rs.getString("document_json"),
                rs.getString("guard_key"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
