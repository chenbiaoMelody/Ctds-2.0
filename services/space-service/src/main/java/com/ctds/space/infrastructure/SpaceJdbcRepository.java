package com.ctds.space.infrastructure;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.space.domain.AccessMode;
import com.ctds.space.domain.AdmissionStatus;
import com.ctds.space.domain.AdmissionType;
import com.ctds.space.domain.MemberRole;
import com.ctds.space.domain.MemberStatus;
import com.ctds.space.domain.PolicyStatus;
import com.ctds.space.domain.SceneType;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceActionLog;
import com.ctds.space.domain.SpaceAdmission;
import com.ctds.space.domain.SpaceErrorCodes;
import com.ctds.space.domain.SpaceMember;
import com.ctds.space.domain.SpaceRepository;
import com.ctds.space.domain.SpaceStatus;
import com.ctds.space.domain.SpaceBizException;
import com.ctds.space.domain.TargetType;
import com.ctds.space.domain.Visibility;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL 空间仓储（JdbcClient，ADR-009 迁移规范建表，沿 subject SubjectJdbcRepository 先例）。
 * 状态门槛 = UPDATE 带 from_status 前置条件（乐观并发控制，0 行即拒——不先查后改，TOCTOU 防护）；
 * 解散三写与留痕同事务（3.2.2 hifi §2 契约）；唯一索引兜底并发创建窗口（DuplicateKeyException → 1006C0003）。
 */
@Repository
public class SpaceJdbcRepository implements SpaceRepository {

    private static final String SPACE_COLUMNS = "id, name, normalized_name, scene_type, access_mode, visibility, "
            + "intro, effective_from, effective_to, owner_subject_no, status, created_at, updated_at";
    private static final String MEMBER_COLUMNS = "id, space_id, subject_no, role, status, joined_at, exited_at, "
            + "created_at, updated_at";
    private static final String ADMISSION_COLUMNS = "id, space_id, subject_no, type, status, operator, reason, "
            + "member_id, created_at, updated_at";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public SpaceJdbcRepository(final JdbcClient jdbc, final JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Space> findById(final long id) {
        return jdbc.sql("SELECT " + SPACE_COLUMNS + " FROM space WHERE id = ?")
                .param(id)
                .query((rs, rowNum) -> mapSpace(rs))
                .optional();
    }

    @Override
    public boolean existsInNameLock(final String normalizedName) {
        final long count = jdbc.sql("SELECT COUNT(*) FROM space_name_lock WHERE normalized_name = ?")
                .param(normalizedName)
                .query(Long.class)
                .single();
        return count > 0;
    }

    @Override
    public boolean existsByOwnerAndNormalizedName(final String ownerSubjectNo, final String normalizedName) {
        final long count = jdbc.sql("SELECT COUNT(*) FROM space WHERE owner_subject_no = ? AND normalized_name = ?")
                .params(ownerSubjectNo, normalizedName)
                .query(Long.class)
                .single();
        return count > 0;
    }

    @Override
    public PageResult<Space> search(final boolean operatorView, final String normalizedNamePrefix,
            final PageQuery page) {
        // 终态（DISSOLVED）不出现在可检索面（hifi §8）；operatorView = platform.operator 全量可见性
        final StringBuilder where = new StringBuilder("status <> ?");
        final List<Object> params = new ArrayList<>();
        params.add(SpaceStatus.DISSOLVED.name());
        if (normalizedNamePrefix != null) {
            where.append(" AND normalized_name LIKE ? ESCAPE '\\\\'");
            params.add(escapeLikePrefix(normalizedNamePrefix) + "%");
        }
        if (!operatorView) {
            where.append(" AND visibility = ?");
            params.add(Visibility.PUBLIC.name());
        }
        final String whereSql = where.toString();
        final long total = jdbc.sql("SELECT COUNT(*) FROM space WHERE " + whereSql)
                .params(params)
                .query(Long.class)
                .single();
        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.pageSize());
        pageParams.add(page.offset());
        final List<Space> list = jdbc.sql("SELECT " + SPACE_COLUMNS + " FROM space WHERE " + whereSql
                        + " ORDER BY id LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> mapSpace(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    @Override
    public List<SpaceMember> findActiveMembers(final long spaceId) {
        return jdbc.sql("SELECT " + MEMBER_COLUMNS + " FROM space_member "
                        + "WHERE space_id = ? AND status = ? ORDER BY id")
                .params(spaceId, MemberStatus.ACTIVE.name())
                .query((rs, rowNum) -> mapMember(rs))
                .list();
    }

    @Override
    @Transactional
    public long create(final Space space, final SpaceMember ownerMember, final SpaceActionLog log) {
        try {
            jdbc.sql("INSERT INTO space (name, normalized_name, scene_type, access_mode, visibility, intro, "
                            + "effective_from, effective_to, owner_subject_no, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(space.name(), space.normalizedName(), space.sceneType().name(),
                            space.accessMode().name(), space.visibility().name(), space.intro(),
                            timestamp(space.effectiveFrom()), timestamp(space.effectiveTo()),
                            space.ownerSubjectNo(), space.status().name(),
                            timestamp(space.createdAt()), timestamp(space.updatedAt()))
                    .update();
        } catch (final DuplicateKeyException e) {
            // uk_owner_norm_name 兜底并发创建窗口（与领域服务层预检构成双保险，沿 subject 先例）
            throw new SpaceBizException(SpaceErrorCodes.SPACE_NAME_TAKEN,
                    SpaceErrorCodes.SPACE_NAME_TAKEN_MESSAGE);
        }
        final long id = jdbc.sql("SELECT id FROM space WHERE owner_subject_no = ? AND normalized_name = ?")
                .params(space.ownerSubjectNo(), space.normalizedName())
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO space_member (space_id, subject_no, role, status, joined_at) VALUES (?, ?, ?, ?, ?)")
                .params(id, ownerMember.subjectNo(), ownerMember.role().name(),
                        ownerMember.status().name(), timestamp(ownerMember.joinedAt()))
                .update();
        insertLogWithinTransaction(id, log);
        return id;
    }

    @Override
    @Transactional
    public void appendTransition(final long spaceId, final SpaceStatus fromStatus, final SpaceStatus toStatus,
            final SpaceActionLog log) {
        final int updatedRows = jdbc.sql("UPDATE space SET status = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ?")
                .params(toStatus.name(), timestamp(log.createdAt()), spaceId, fromStatus.name())
                .update();
        if (updatedRows == 0) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_STATUS_GATE,
                    SpaceErrorCodes.SPACE_STATUS_GATE_MESSAGE);
        }
        insertLogWithinTransaction(spaceId, withFromTo(log, fromStatus.name(), toStatus.name()));
    }

    @Override
    @Transactional
    public void dissolve(final long spaceId, final SpaceStatus fromStatus, final String normalizedName,
            final SpaceActionLog log) {
        // 三写①：状态乐观门槛更新（fromStatus 精确匹配；0 行 = 状态门槛拒绝 1006C0002；
        // 终态自环由应用服务前置门槛拦截——同值 WHERE 拦不住 DISSOLVED→DISSOLVED）
        final int updatedRows = jdbc.sql("UPDATE space SET status = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ?")
                .params(SpaceStatus.DISSOLVED.name(), timestamp(log.createdAt()), spaceId, fromStatus.name())
                .update();
        if (updatedRows == 0) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_STATUS_GATE,
                    SpaceErrorCodes.SPACE_STATUS_GATE_MESSAGE);
        }
        // 三写②：名称锁定（INSERT...SELECT...WHERE NOT EXISTS = 已锁定即跳过，Q6-A"锁定目标已达成"；
        // 并发同名解散竞态下 PK 冲突同样按"已锁定即跳过"处置——跳过时留痕仍记 DISSOLVED）
        try {
            jdbc.sql("INSERT INTO space_name_lock (normalized_name, space_id, locked_at) "
                            + "SELECT ?, ?, ? FROM DUAL "
                            + "WHERE NOT EXISTS (SELECT 1 FROM space_name_lock WHERE normalized_name = ?)")
                    .params(normalizedName, spaceId, timestamp(log.createdAt()), normalizedName)
                    .update();
        } catch (final DuplicateKeyException e) {
            // skip: 锁定目标已达成（Q6-A），不阻断治理兜底动作
        }
        // 三写③：该空间策略条目全部归档（行为 7 规则 5"归档不可变、保留可查"）
        jdbc.sql("UPDATE space_policy SET status = ?, updated_at = ? "
                        + "WHERE space_id = ? AND status = ?")
                .params(PolicyStatus.ARCHIVED.name(), timestamp(log.createdAt()), spaceId,
                        PolicyStatus.ACTIVE.name())
                .update();
        insertLogWithinTransaction(spaceId, withFromTo(log, fromStatus.name(), SpaceStatus.DISSOLVED.name()));
    }

    @Override
    @Transactional
    public void updateFields(final long spaceId, final SpaceUpdate update, final List<SpaceActionLog> logs) {
        final List<String> sets = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        if (update.intro() != null) {
            sets.add("intro = ?");
            params.add(update.intro());
        }
        if (update.effectiveFrom() != null) {
            sets.add("effective_from = ?");
            params.add(timestamp(update.effectiveFrom()));
        }
        if (update.effectiveTo() != null) {
            sets.add("effective_to = ?");
            params.add(timestamp(update.effectiveTo()));
        }
        if (sets.isEmpty()) {
            return;
        }
        // status <> DISSOLVED 守卫：服务层门槛判定与写入之间的窗口内被并发解散时拒改（0 行 → 1006C0002）
        final List<Object> updateParams = new ArrayList<>(params);
        updateParams.add(timestamp(logs.get(0).createdAt()));
        updateParams.add(spaceId);
        updateParams.add(SpaceStatus.DISSOLVED.name());
        final int updatedRows = jdbc.sql("UPDATE space SET " + String.join(", ", sets)
                        + ", updated_at = ? WHERE id = ? AND status <> ?")
                .params(updateParams)
                .update();
        if (updatedRows == 0) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_STATUS_GATE,
                    SpaceErrorCodes.SPACE_STATUS_GATE_MESSAGE);
        }
        for (final SpaceActionLog log : logs) {
            insertLogWithinTransaction(spaceId, log);
        }
    }

    @Override
    public void insertLog(final SpaceActionLog log) {
        insertLogWithinTransaction(log.spaceId(), log);
    }

    // ==== 成员与准入（WBS-3.2.4 实现）====

    @Override
    public Optional<SpaceAdmission> findAdmissionById(final long admissionId) {
        return jdbc.sql("SELECT " + ADMISSION_COLUMNS + " FROM space_admission WHERE id = ?")
                .param(admissionId)
                .query((rs, rowNum) -> mapAdmission(rs))
                .optional();
    }

    @Override
    public boolean existsActiveMembership(final long spaceId, final String subjectNo) {
        final long count = jdbc.sql("SELECT COUNT(*) FROM space_member "
                        + "WHERE space_id = ? AND subject_no = ? AND status = ?")
                .params(spaceId, subjectNo, MemberStatus.ACTIVE.name())
                .query(Long.class)
                .single();
        return count > 0;
    }

    @Override
    public Optional<SpaceAdmission> findPendingAdmission(final long spaceId, final String subjectNo,
            final AdmissionType type) {
        return jdbc.sql("SELECT " + ADMISSION_COLUMNS + " FROM space_admission "
                        + "WHERE space_id = ? AND subject_no = ? AND type = ? AND status IN (?, ?) "
                        + "ORDER BY id DESC LIMIT 1")
                .params(spaceId, subjectNo, type.name(), AdmissionStatus.PENDING_APPROVAL.name(),
                        AdmissionStatus.PENDING_CONFIRMATION.name())
                .query((rs, rowNum) -> mapAdmission(rs))
                .optional();
    }

    @Override
    public Optional<SpaceMember> findMemberById(final long memberId) {
        return jdbc.sql("SELECT " + MEMBER_COLUMNS + " FROM space_member WHERE id = ?")
                .param(memberId)
                .query((rs, rowNum) -> mapMember(rs))
                .optional();
    }

    @Override
    @Transactional
    public long insertAdmission(final SpaceAdmission admission, final SpaceActionLog log) {
        // 准入单无唯一键可回查——GeneratedKeyHolder 取自增主键（沿 MySQL 先例）；创建留痕同事务（审计无缺口）
        final KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            final PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO space_admission (space_id, subject_no, type, status, operator, reason, "
                            + "member_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, admission.spaceId());
            ps.setString(2, admission.subjectNo());
            ps.setString(3, admission.type().name());
            ps.setString(4, admission.status().name());
            ps.setString(5, admission.operator());
            ps.setString(6, admission.reason());
            ps.setObject(7, admission.memberId());
            ps.setTimestamp(8, timestamp(admission.createdAt()));
            ps.setTimestamp(9, timestamp(admission.updatedAt()));
            return ps;
        }, keyHolder);
        final long id = keyHolder.getKey().longValue();
        insertLogWithinTransaction(admission.spaceId(), withFromTo(log, null, admission.status().name()));
        return id;
    }

    @Override
    @Transactional
    public long activateMembership(final SpaceMember member, final long admissionId, final long spaceId,
            final AdmissionStatus fromStatus, final SpaceActionLog log) {
        try {
            // 单事务三步①：成员行 INSERT（uk_active_member 兜底并发重复准入窗口——INSERT 在 try 内，
            // 冲突=并发方已建立活跃成员行，catch 回查既有行返回；本方准入单 UPDATE 随后 0 行 → 0010 回滚）
            jdbc.sql("INSERT INTO space_member (space_id, subject_no, role, status, joined_at) "
                            + "VALUES (?, ?, ?, ?, ?)")
                    .params(member.spaceId(), member.subjectNo(), member.role().name(), member.status().name(),
                            timestamp(member.joinedAt()))
                    .update();
            final long memberId = jdbc.sql("SELECT id FROM space_member "
                            + "WHERE space_id = ? AND subject_no = ? AND status = ?")
                    .params(spaceId, member.subjectNo(), MemberStatus.ACTIVE.name())
                    .query(Long.class)
                    .single();
            // 单事务三步②：准入单乐观门槛更新 + 回填 member_id（0 行 → 1006C0010）
            final int updatedRows = jdbc.sql("UPDATE space_admission SET status = ?, member_id = ?, "
                            + "updated_at = ? WHERE id = ? AND space_id = ? AND status = ?")
                    .params(AdmissionStatus.APPROVED.name(), memberId, timestamp(log.createdAt()),
                            admissionId, spaceId, fromStatus.name())
                    .update();
            if (updatedRows == 0) {
                throw new SpaceBizException(SpaceErrorCodes.ADMISSION_STATE_GATE,
                        SpaceErrorCodes.ADMISSION_STATE_GATE_MESSAGE);
            }
            // 单事务三步③：留痕（from/to 由参数回填——移交④）
            insertLogWithinTransaction(spaceId, withFromTo(log, fromStatus.name(),
                    AdmissionStatus.APPROVED.name()));
            return memberId;
        } catch (final DuplicateKeyException e) {
            // uk_active_member 冲突 = 并发方已建立同一活跃成员行（Q3-A 语义：返回既有关系）——
            // 回查既有行 id；本方准入单 UPDATE 因状态已被并发方推进而 0 行 → 0010 整体回滚，不产生双成员
            return jdbc.sql("SELECT id FROM space_member WHERE space_id = ? AND subject_no = ? "
                            + "AND status = ?")
                    .params(spaceId, member.subjectNo(), MemberStatus.ACTIVE.name())
                    .query(Long.class)
                    .single();
        }
    }

    @Override
    @Transactional
    public void appendAdmissionTransition(final long admissionId, final long spaceId,
            final AdmissionStatus fromStatus, final AdmissionStatus toStatus, final SpaceActionLog log) {
        final int updatedRows = jdbc.sql("UPDATE space_admission SET status = ?, updated_at = ? "
                        + "WHERE id = ? AND space_id = ? AND status = ?")
                .params(toStatus.name(), timestamp(log.createdAt()), admissionId, spaceId, fromStatus.name())
                .update();
        if (updatedRows == 0) {
            throw new SpaceBizException(SpaceErrorCodes.ADMISSION_STATE_GATE,
                    SpaceErrorCodes.ADMISSION_STATE_GATE_MESSAGE);
        }
        insertLogWithinTransaction(spaceId, withFromTo(log, fromStatus.name(), toStatus.name()));
    }

    @Override
    public PageResult<SpaceAdmission> searchAdmissions(final long spaceId, final AdmissionStatus status,
            final PageQuery page) {
        final List<Object> params = new ArrayList<>();
        params.add(spaceId);
        String where = "space_id = ?";
        if (status != null) {
            where += " AND status = ?";
            params.add(status.name());
        }
        return pageAdmissions(where, params, page, " ORDER BY id");
    }

    @Override
    public PageResult<SpaceAdmission> searchAdmissionsBySubject(final String subjectNo, final PageQuery page) {
        return pageAdmissions("subject_no = ?", List.of(subjectNo), page, " ORDER BY id DESC");
    }

    @Override
    public PageResult<SpaceMember> searchActiveMembers(final long spaceId, final PageQuery page) {
        final long total = jdbc.sql("SELECT COUNT(*) FROM space_member "
                        + "WHERE space_id = ? AND status = ?")
                .params(spaceId, MemberStatus.ACTIVE.name())
                .query(Long.class)
                .single();
        final List<SpaceMember> list = jdbc.sql("SELECT " + MEMBER_COLUMNS + " FROM space_member "
                        + "WHERE space_id = ? AND status = ? ORDER BY id LIMIT ? OFFSET ?")
                .params(spaceId, MemberStatus.ACTIVE.name(), page.pageSize(), page.offset())
                .query((rs, rowNum) -> mapMember(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    @Override
    @Transactional
    public void terminateMembership(final long memberId, final long spaceId, final MemberRole fromRole,
            final MemberStatus terminalStatus, final SpaceActionLog log) {
        final int updatedRows = jdbc.sql("UPDATE space_member SET status = ?, exited_at = ?, updated_at = ? "
                        + "WHERE id = ? AND space_id = ? AND status = ?")
                .params(terminalStatus.name(), timestamp(log.createdAt()), timestamp(log.createdAt()),
                        memberId, spaceId, MemberStatus.ACTIVE.name())
                .update();
        if (updatedRows == 0) {
            throw new SpaceBizException(SpaceErrorCodes.MEMBER_RELATION_REQUIRED,
                    SpaceErrorCodes.MEMBER_RELATION_REQUIRED_MESSAGE);
        }
        insertLogWithinTransaction(spaceId, withFromTo(log, fromRole.name(), terminalStatus.name()));
    }

    @Override
    @Transactional
    public void changeRole(final long memberId, final long spaceId, final MemberRole fromRole,
            final MemberRole toRole, final SpaceActionLog log) {
        final int updatedRows = jdbc.sql("UPDATE space_member SET role = ?, updated_at = ? "
                        + "WHERE id = ? AND space_id = ? AND status = ? AND role = ?")
                .params(toRole.name(), timestamp(log.createdAt()), memberId, spaceId,
                        MemberStatus.ACTIVE.name(), fromRole.name())
                .update();
        if (updatedRows == 0) {
            throw new SpaceBizException(SpaceErrorCodes.MEMBER_RELATION_REQUIRED,
                    SpaceErrorCodes.MEMBER_RELATION_REQUIRED_MESSAGE);
        }
        insertLogWithinTransaction(spaceId, withFromTo(log, fromRole.name(), toRole.name()));
    }

    @Override
    @Transactional
    public void transferOwnership(final long spaceId, final String currentOwnerSubjectNo,
            final String targetSubjectNo, final long targetMemberId, final MemberRole formerOwnerNewRole,
            final SpaceActionLog grantLog, final SpaceActionLog revokeLog) {
        // 写①：原 owner 成员行降级（先降后升避 uk_active_owner 冲突；0 行 = owner 行异常 → 1006C0008）
        final int demoted = jdbc.sql("UPDATE space_member SET role = ?, updated_at = ? "
                        + "WHERE space_id = ? AND subject_no = ? AND status = ? AND role = ?")
                .params(formerOwnerNewRole.name(), timestamp(grantLog.createdAt()), spaceId,
                        currentOwnerSubjectNo, MemberStatus.ACTIVE.name(), MemberRole.OWNER.name())
                .update();
        if (demoted == 0) {
            throw new SpaceBizException(SpaceErrorCodes.MEMBER_RELATION_REQUIRED,
                    SpaceErrorCodes.MEMBER_RELATION_REQUIRED_MESSAGE);
        }
        // 写②：目标成员行升 OWNER（0 行 = 目标不可用 → 1006C0008；并发双转移窗口 uk_active_owner 兜底）
        try {
            final int promoted = jdbc.sql("UPDATE space_member SET role = ?, updated_at = ? "
                            + "WHERE id = ? AND space_id = ? AND status = ? AND role <> ?")
                    .params(MemberRole.OWNER.name(), timestamp(grantLog.createdAt()), targetMemberId,
                            spaceId, MemberStatus.ACTIVE.name(), MemberRole.OWNER.name())
                    .update();
            if (promoted == 0) {
                throw new SpaceBizException(SpaceErrorCodes.MEMBER_RELATION_REQUIRED,
                        SpaceErrorCodes.MEMBER_RELATION_REQUIRED_MESSAGE);
            }
        } catch (final DuplicateKeyException e) {
            throw new SpaceBizException(SpaceErrorCodes.OWNER_PROTECTED,
                    SpaceErrorCodes.OWNER_PROTECTED_MESSAGE);
        }
        // 写③：space.owner_subject_no 列乐观门槛同步（移交②双处同步；0 行 = 并发变更 → 1006C0008）
        final int ownerColumnUpdated = jdbc.sql("UPDATE space SET owner_subject_no = ?, updated_at = ? "
                        + "WHERE id = ? AND owner_subject_no = ?")
                .params(targetSubjectNo, timestamp(grantLog.createdAt()), spaceId, currentOwnerSubjectNo)
                .update();
        if (ownerColumnUpdated == 0) {
            throw new SpaceBizException(SpaceErrorCodes.MEMBER_RELATION_REQUIRED,
                    SpaceErrorCodes.MEMBER_RELATION_REQUIRED_MESSAGE);
        }
        // 写④：留痕两行（grantLog = 目标升 OWNER / revokeLog = 原 owner 降级；from/to 为业务构造值）
        insertLogWithinTransaction(spaceId, grantLog);
        insertLogWithinTransaction(spaceId, revokeLog);
    }

    /**
     * 事务内留痕写入（同事务契约的落库点）。target_id 兜底：目标类型为空间而调用方未指实体时
     * 以 space_id 回填（V1 契约"探测被拒且无实体可指才允许 NULL"——CREATE 后实体已存在，
     * 按 target_id 检索留痕不可漏行，评审循环 1 补）。
     */
    private void insertLogWithinTransaction(final long spaceId, final SpaceActionLog log) {
        final Long targetId = log.targetId() != null ? log.targetId()
                : (log.targetType() == TargetType.SPACE ? spaceId : null);
        jdbc.sql("INSERT INTO space_action_log (space_id, target_type, target_id, action, operator, "
                        + "from_value, to_value, result, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(spaceId, log.targetType().name(), targetId, log.action(), log.operator(),
                        log.fromValue(), log.toValue(), log.result().name(), log.reason(),
                        timestamp(log.createdAt()))
                .update();
    }

    private Space mapSpace(final ResultSet rs) throws SQLException {
        return new Space(rs.getLong("id"), rs.getString("name"), rs.getString("normalized_name"),
                SceneType.valueOf(rs.getString("scene_type")), AccessMode.valueOf(rs.getString("access_mode")),
                Visibility.valueOf(rs.getString("visibility")), rs.getString("intro"),
                toLocalDateTime(rs.getTimestamp("effective_from")),
                toLocalDateTime(rs.getTimestamp("effective_to")),
                rs.getString("owner_subject_no"), SpaceStatus.valueOf(rs.getString("status")),
                toLocalDateTime(rs.getTimestamp("created_at")), toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    private SpaceMember mapMember(final ResultSet rs) throws SQLException {
        return new SpaceMember(rs.getLong("id"), rs.getLong("space_id"), rs.getString("subject_no"),
                MemberRole.valueOf(rs.getString("role")), MemberStatus.valueOf(rs.getString("status")),
                toLocalDateTime(rs.getTimestamp("joined_at")), toLocalDateTime(rs.getTimestamp("exited_at")),
                toLocalDateTime(rs.getTimestamp("created_at")), toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    private SpaceAdmission mapAdmission(final ResultSet rs) throws SQLException {
        // member_id 判空必须紧跟 getLong（rs.wasNull 只看最近一列——中间穿插 getString 会错位）
        final long memberId = rs.getLong("member_id");
        final boolean memberMissing = rs.wasNull();
        return new SpaceAdmission(rs.getLong("id"), rs.getLong("space_id"), rs.getString("subject_no"),
                AdmissionType.valueOf(rs.getString("type")), AdmissionStatus.valueOf(rs.getString("status")),
                rs.getString("operator"), rs.getString("reason"), memberMissing ? null : memberId,
                toLocalDateTime(rs.getTimestamp("created_at")), toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    /** 准入单分页公共段（COUNT + LIMIT/OFFSET 两段同 WHERE，沿 search 先例）。 */
    private PageResult<SpaceAdmission> pageAdmissions(final String where, final List<Object> params,
            final PageQuery page, final String order) {
        final long total = jdbc.sql("SELECT COUNT(*) FROM space_admission WHERE " + where)
                .params(params)
                .query(Long.class)
                .single();
        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.pageSize());
        pageParams.add(page.offset());
        final List<SpaceAdmission> list = jdbc.sql("SELECT " + ADMISSION_COLUMNS
                        + " FROM space_admission WHERE " + where + order + " LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> mapAdmission(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    /**
     * 留痕 from/to 单一表达回填（WBS-3.2.4 移交④收敛）：以方法参数覆盖调用方构造值，
     * 防"乐观门槛参数与留痕载体"双表达漂移（一致性锚 T20）。
     */
    private static SpaceActionLog withFromTo(final SpaceActionLog log, final String fromValue,
            final String toValue) {
        return new SpaceActionLog(log.id(), log.spaceId(), log.targetType(), log.targetId(), log.action(),
                log.operator(), fromValue, toValue, log.result(), log.reason(), log.createdAt());
    }

    private static Timestamp timestamp(final LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime toLocalDateTime(final Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    /** LIKE 前缀转义（\\ % _），防通配符注入放大检索语义；SQL 侧 ESCAPE '\\' 对应。 */
    private static String escapeLikePrefix(final String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
