package com.ctds.subject.infrastructure;

import com.ctds.subject.domain.CertMaterial;
import com.ctds.subject.domain.CertVerificationLog;
import com.ctds.subject.domain.CertificationRepository;
import com.ctds.subject.domain.VerificationConclusion;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL 认证仓储（JdbcClient，ADR-009；WBS-3.1.3 hifi 库表设计契约）。
 * 密文列（content_cipher/ocr_raw_cipher/legal_person_id_cipher）以字节流存取，本层不做加解密
 * （L4 唯一入口 common-crypto，加解密只发生在应用层）。
 */
@Repository
public class CertificationJdbcRepository implements CertificationRepository {

    private final JdbcClient jdbc;

    public CertificationJdbcRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public CertMaterial replaceMaterial(final CertMaterial material) {
        jdbc.sql("DELETE FROM cert_material WHERE subject_id = ? AND material_type = ?")
                .params(material.subjectId(), material.materialType())
                .update();
        jdbc.sql("INSERT INTO cert_material (subject_id, material_type, file_name, "
                        + "content_sm3, content_cipher, ocr_raw_cipher, ocr_uscc, ocr_legal_person, "
                        + "ocr_recognizable, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(material.subjectId(), material.materialType(), material.fileName(),
                        material.contentSm3(), material.contentCipher(), material.ocrRawCipher(),
                        material.ocrUscc(), material.ocrLegalPerson(), material.ocrRecognizable() ? 1 : 0,
                        Timestamp.valueOf(material.createdAt()))
                .update();
        final long id = jdbc.sql("SELECT id FROM cert_material WHERE subject_id = ? AND material_type = ? "
                        + "ORDER BY id DESC LIMIT 1")
                .params(material.subjectId(), material.materialType())
                .query(Long.class)
                .single();
        return new CertMaterial(id, material.subjectId(), material.materialType(), material.fileName(),
                material.contentSm3(), material.contentCipher(), material.ocrRawCipher(),
                material.ocrUscc(), material.ocrLegalPerson(), material.ocrRecognizable(),
                null, null, null, null, null, material.createdAt());
    }

    @Override
    public Optional<CertMaterial> findLatestMaterial(final long subjectId, final String materialType) {
        return jdbc.sql("SELECT id, subject_id, material_type, file_name, content_sm3, content_cipher, "
                        + "ocr_raw_cipher, ocr_uscc, ocr_legal_person, ocr_recognizable, confirmed_name, "
                        + "confirmed_uscc, confirmed_legal_person, confirmed_reg_address, confirmed_at, created_at "
                        + "FROM cert_material WHERE subject_id = ? AND material_type = ? "
                        + "ORDER BY id DESC LIMIT 1")
                .params(subjectId, materialType)
                .query(this::mapMaterial)
                .optional();
    }

    @Override
    @Transactional
    public void updateConfirmation(final long materialId, final String confirmedName, final String confirmedUscc,
            final String confirmedLegalPerson, final String confirmedRegAddress,
            final LocalDateTime confirmedAt) {
        jdbc.sql("UPDATE cert_material SET confirmed_name = ?, confirmed_uscc = ?, confirmed_legal_person = ?, "
                        + "confirmed_reg_address = ?, confirmed_at = ? WHERE id = ?")
                .params(confirmedName, confirmedUscc, confirmedLegalPerson, confirmedRegAddress,
                        Timestamp.valueOf(confirmedAt), materialId)
                .update();
    }

    @Override
    @Transactional
    public void appendVerification(final CertVerificationLog log) {
        jdbc.sql("INSERT INTO cert_verification_log (subject_id, verify_type, channel_code, channel_request_no, "
                        + "legal_person_name, legal_person_id_cipher, conclusion, fail_reason, cost_ms, counted, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(log.subjectId(), log.verifyType(), log.channelCode(), log.channelRequestNo(),
                        log.legalPersonName(), log.legalPersonIdCipher(), log.conclusion().name(),
                        log.failReason(), log.costMs(), log.counted() ? 1 : 0,
                        Timestamp.valueOf(log.createdAt()))
                .update();
    }

    @Override
    public List<CertVerificationLog> findVerifications(final long subjectId) {
        return jdbc.sql("SELECT id, subject_id, verify_type, channel_code, channel_request_no, legal_person_name, "
                        + "legal_person_id_cipher, conclusion, fail_reason, cost_ms, counted, created_at "
                        + "FROM cert_verification_log WHERE subject_id = ? ORDER BY id ASC")
                .param(subjectId)
                .query(this::mapLog)
                .list();
    }

    @Override
    public int countFailuresSince(final long subjectId, final LocalDateTime since) {
        final Integer count = jdbc.sql("SELECT COUNT(1) FROM cert_verification_log "
                        + "WHERE subject_id = ? AND conclusion = ? AND counted = 1 AND created_at >= ?")
                .params(subjectId, VerificationConclusion.FAIL.name(), Timestamp.valueOf(since))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private CertMaterial mapMaterial(final java.sql.ResultSet rs, final int rowNum) throws java.sql.SQLException {
        return new CertMaterial(
                rs.getLong("id"),
                rs.getLong("subject_id"),
                rs.getString("material_type"),
                rs.getString("file_name"),
                rs.getString("content_sm3"),
                rs.getBytes("content_cipher"),
                rs.getBytes("ocr_raw_cipher"),
                rs.getString("ocr_uscc"),
                rs.getString("ocr_legal_person"),
                rs.getInt("ocr_recognizable") == 1,
                rs.getString("confirmed_name"),
                rs.getString("confirmed_uscc"),
                rs.getString("confirmed_legal_person"),
                rs.getString("confirmed_reg_address"),
                toLocalDateTime(rs.getTimestamp("confirmed_at")),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private CertVerificationLog mapLog(final java.sql.ResultSet rs, final int rowNum) throws java.sql.SQLException {
        return new CertVerificationLog(
                rs.getLong("id"),
                rs.getLong("subject_id"),
                rs.getString("verify_type"),
                rs.getString("channel_code"),
                rs.getString("channel_request_no"),
                rs.getString("legal_person_name"),
                rs.getString("legal_person_id_cipher"),
                VerificationConclusion.valueOf(rs.getString("conclusion")),
                rs.getString("fail_reason"),
                rs.getInt("cost_ms"),
                rs.getInt("counted") == 1,
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private static LocalDateTime toLocalDateTime(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
