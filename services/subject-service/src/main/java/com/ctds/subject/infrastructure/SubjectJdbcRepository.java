package com.ctds.subject.infrastructure;

import com.ctds.common.errorcode.BizException;
import com.ctds.subject.domain.StatusTransition;
import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.SubjectErrorCodes;
import com.ctds.subject.domain.SubjectRepository;
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.SubjectType;
import com.ctds.subject.domain.TriggerRole;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL 主体仓储（JdbcClient，ADR-009 迁移规范建表）。
 * create/resubmit 同事务落库（档案 + 流转留痕），保证"档案与留痕要么都有要么都没有"；
 * uk_uscc 唯一索引兜底并发注册窗口（与领域服务层预检构成双保险，hifi 边界表）。
 */
@Repository
public class SubjectJdbcRepository implements SubjectRepository {

    private final JdbcClient jdbc;

    public SubjectJdbcRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void create(final Subject subject, final StatusTransition initialTransition) {
        try {
            jdbc.sql("INSERT INTO subject (subject_no, subject_name, uscc, subject_type, reg_address, "
                            + "contact_name, contact_phone, admin_account, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(subject.subjectNo(), subject.subjectName(), subject.uscc(),
                            subject.subjectType().name(), subject.regAddress(), subject.contactName(),
                            subject.contactPhone(), subject.adminAccount(), subject.status().name(),
                            Timestamp.valueOf(subject.createdAt()), Timestamp.valueOf(subject.updatedAt()))
                    .update();
        } catch (final DuplicateKeyException e) {
            throw new BizException(SubjectErrorCodes.SUBJECT_ALREADY_REGISTERED, "该主体已注册");
        }
        final long id = jdbc.sql("SELECT id FROM subject WHERE subject_no = ?")
                .param(subject.subjectNo())
                .query(Long.class)
                .single();
        insertTransition(id, initialTransition);
    }

    @Override
    @Transactional
    public void resubmit(final Subject updated, final StatusTransition transition) {
        final int updatedRows = jdbc.sql("UPDATE subject SET subject_name = ?, subject_type = ?, reg_address = ?, "
                        + "contact_name = ?, contact_phone = ?, admin_account = ?, status = ?, updated_at = ? "
                        + "WHERE id = ?")
                .params(updated.subjectName(), updated.subjectType().name(), updated.regAddress(),
                        updated.contactName(), updated.contactPhone(), updated.adminAccount(),
                        updated.status().name(), Timestamp.valueOf(updated.updatedAt()), updated.id())
                .update();
        if (updatedRows == 0) {
            throw new BizException(SubjectErrorCodes.SUBJECT_CANCEL_NOT_ALLOWED, "当前状态不可撤销");
        }
        insertTransition(updated.id(), transition);
    }

    @Override
    public void appendTransition(final long subjectId, final StatusTransition transition) {
        insertTransition(subjectId, transition);
    }

    @Override
    public Optional<Subject> findBySubjectNo(final String subjectNo) {
        return querySubject("SELECT id, subject_no, subject_name, uscc, subject_type, reg_address, contact_name, "
                + "contact_phone, admin_account, status, created_at, updated_at FROM subject "
                + "WHERE subject_no = ?", subjectNo);
    }

    @Override
    public Optional<Subject> findByUscc(final String uscc) {
        return querySubject("SELECT id, subject_no, subject_name, uscc, subject_type, reg_address, contact_name, "
                + "contact_phone, admin_account, status, created_at, updated_at FROM subject "
                + "WHERE uscc = ?", uscc);
    }

    @Override
    public Optional<StatusTransition> findLatestTransition(final long subjectId) {
        return jdbc.sql("SELECT from_status, to_status, trigger_role, operator, remark, created_at "
                        + "FROM subject_status_log WHERE subject_id = ? ORDER BY id DESC LIMIT 1")
                .param(subjectId)
                .query((rs, rowNum) -> new StatusTransition(
                        statusOrNull(rs.getString("from_status")),
                        SubjectStatus.valueOf(rs.getString("to_status")),
                        TriggerRole.valueOf(rs.getString("trigger_role")),
                        rs.getString("operator"),
                        rs.getString("remark"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .optional();
    }

    @Override
    public List<StatusTransition> findTransitions(final long subjectId) {
        return jdbc.sql("SELECT from_status, to_status, trigger_role, operator, remark, created_at "
                        + "FROM subject_status_log WHERE subject_id = ? ORDER BY id ASC")
                .param(subjectId)
                .query((rs, rowNum) -> new StatusTransition(
                        statusOrNull(rs.getString("from_status")),
                        SubjectStatus.valueOf(rs.getString("to_status")),
                        TriggerRole.valueOf(rs.getString("trigger_role")),
                        rs.getString("operator"),
                        rs.getString("remark"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }

    @Override
    public int nextDailySeq(final LocalDate date) {
        jdbc.sql("INSERT INTO subject_daily_seq (seq_date, seq_val) VALUES (?, 1) "
                        + "ON DUPLICATE KEY UPDATE seq_val = seq_val + 1")
                .param(Date.valueOf(date))
                .update();
        return jdbc.sql("SELECT seq_val FROM subject_daily_seq WHERE seq_date = ?")
                .param(Date.valueOf(date))
                .query(Integer.class)
                .single();
    }

    private Optional<Subject> querySubject(final String sql, final String key) {
        return jdbc.sql(sql)
                .param(key)
                .query((rs, rowNum) -> new Subject(
                        rs.getLong("id"),
                        rs.getString("subject_no"),
                        rs.getString("subject_name"),
                        rs.getString("uscc"),
                        SubjectType.valueOf(rs.getString("subject_type")),
                        rs.getString("reg_address"),
                        rs.getString("contact_name"),
                        rs.getString("contact_phone"),
                        rs.getString("admin_account"),
                        SubjectStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()))
                .optional();
    }

    private void insertTransition(final long subjectId, final StatusTransition transition) {
        jdbc.sql("INSERT INTO subject_status_log (subject_id, from_status, to_status, trigger_role, operator, "
                        + "remark, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(subjectId, transition.fromStatus() == null ? "NONE" : transition.fromStatus().name(),
                        transition.toStatus().name(), transition.triggerRole().name(), transition.operator(),
                        transition.remark(), Timestamp.valueOf(transition.createdAt()))
                .update();
    }

    private static SubjectStatus statusOrNull(final String value) {
        return "NONE".equals(value) ? null : SubjectStatus.valueOf(value);
    }
}
