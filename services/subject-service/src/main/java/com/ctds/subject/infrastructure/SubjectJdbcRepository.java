package com.ctds.subject.infrastructure;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
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
                            + "contact_name, contact_phone, admin_account, applicant, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(subject.subjectNo(), subject.subjectName(), subject.uscc(),
                            subject.subjectType().name(), subject.regAddress(), subject.contactName(),
                            subject.contactPhone(), subject.adminAccount(), subject.applicant(),
                            subject.status().name(),
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
        final int updatedRows = jdbc.sql("UPDATE subject SET subject_name = ?, reg_address = ?, "
                        + "contact_name = ?, contact_phone = ?, admin_account = ?, status = ?, updated_at = ? "
                        + "WHERE id = ?")
                .params(updated.subjectName(), updated.regAddress(),
                        updated.contactName(), updated.contactPhone(), updated.adminAccount(),
                        updated.status().name(), Timestamp.valueOf(updated.updatedAt()), updated.id())
                .update();
        if (updatedRows == 0) {
            throw new BizException(SubjectErrorCodes.SUBJECT_CANCEL_NOT_ALLOWED, "当前状态不可撤销");
        }
        insertTransition(updated.id(), transition);
    }

    /**
     * 追加流转留痕并同步更新主体状态列（WBS-3.1.3 评审修复：流转必须落库 subject.status，
     * 否则核验通过/结束认证后库内状态原地不动、审核端查不到待审核主体）。
     * 状态门槛 = UPDATE 带 from_status 前置条件（乐观并发控制）：并发重复流转只有第一个事务成功，
     * 其余 0 行 → 1004C0002（hifi 边界值"transition() 状态门槛拒绝"的实现落点）。
     * from_status 为 null（注册建档 NONE）不经本方法（create 走 insertTransition 直插）。
     */
    @Override
    @Transactional
    public void appendTransition(final long subjectId, final StatusTransition transition) {
        final int updatedRows = jdbc.sql("UPDATE subject SET status = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ?")
                .params(transition.toStatus().name(), Timestamp.valueOf(transition.createdAt()),
                        subjectId, transition.fromStatus().name())
                .update();
        if (updatedRows == 0) {
            throw new BizException(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED, "当前状态不允许执行认证操作");
        }
        insertTransition(subjectId, transition);
    }

    @Override
    public Optional<Subject> findBySubjectNo(final String subjectNo) {
        return querySubject("SELECT id, subject_no, subject_name, uscc, subject_type, reg_address, contact_name, "
                + "contact_phone, admin_account, applicant, status, created_at, updated_at FROM subject "
                + "WHERE subject_no = ?", subjectNo);
    }

    @Override
    public Optional<Subject> findByUscc(final String uscc) {
        return querySubject("SELECT id, subject_no, subject_name, uscc, subject_type, reg_address, contact_name, "
                + "contact_phone, admin_account, applicant, status, created_at, updated_at FROM subject "
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

    /**
     * 当日序号原子取号：LAST_INSERT_ID(expr) 在自增的同时写入连接级返回值，自增与读取不跨语句竞态
     * （hifi 库表设计"原子取号"契约）；事务绑定保证两条语句共用同一连接（LAST_INSERT_ID 为连接级）。
     * 新插入路径写 LAST_INSERT_ID(1)（首次序号 = 1，且覆盖连接池残留的上一轮取号值）；
     * 重复键走 UPDATE 路径写 LAST_INSERT_ID(seq_val + 1)——两条路径均产生本连接确定值。
     * 当日容量上限 999999（申请编号 6 位序号段），超出按超限错误处理而非溢出编号。
     */
    @Override
    @Transactional
    public int nextDailySeq(final LocalDate date) {
        jdbc.sql("INSERT INTO subject_daily_seq (seq_date, seq_val) VALUES (?, LAST_INSERT_ID(1)) "
                        + "ON DUPLICATE KEY UPDATE seq_val = LAST_INSERT_ID(seq_val + 1)")
                .param(Date.valueOf(date))
                .update();
        final int seq = jdbc.sql("SELECT LAST_INSERT_ID()")
                .query(Integer.class)
                .single();
        if (seq > 999_999) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "当日申请编号序号已达上限");
        }
        return seq;
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
                        rs.getString("applicant"),
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
