package com.ctds.space.infrastructure;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.space.domain.AccessMode;
import com.ctds.space.domain.MemberRole;
import com.ctds.space.domain.MemberStatus;
import com.ctds.space.domain.SceneType;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceActionLog;
import com.ctds.space.domain.SpaceErrorCodes;
import com.ctds.space.domain.SpaceMember;
import com.ctds.space.domain.SpaceRepository;
import com.ctds.space.domain.SpaceStatus;
import com.ctds.space.domain.SpaceBizException;
import com.ctds.space.domain.Visibility;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
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

    private final JdbcClient jdbc;

    public SpaceJdbcRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
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
        final StringBuilder where = new StringBuilder("status <> 'DISSOLVED'");
        final List<Object> params = new ArrayList<>();
        if (normalizedNamePrefix != null) {
            where.append(" AND normalized_name LIKE ? ESCAPE '\\\\'");
            params.add(escapeLikePrefix(normalizedNamePrefix) + "%");
        }
        if (!operatorView) {
            where.append(" AND visibility = 'PUBLIC'");
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
                        + "WHERE space_id = ? AND status = 'ACTIVE' ORDER BY id")
                .param(spaceId)
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
        insertLogWithinTransaction(spaceId, log);
    }

    @Override
    @Transactional
    public void dissolve(final long spaceId, final SpaceStatus fromStatus, final String normalizedName,
            final SpaceActionLog log) {
        // 三写①：状态乐观门槛更新（fromStatus 精确匹配；0 行 = 状态门槛拒绝 1006C0002）
        final int updatedRows = jdbc.sql("UPDATE space SET status = 'DISSOLVED', updated_at = ? "
                        + "WHERE id = ? AND status = ?")
                .params(timestamp(log.createdAt()), spaceId, fromStatus.name())
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
        jdbc.sql("UPDATE space_policy SET status = 'ARCHIVED', updated_at = ? "
                        + "WHERE space_id = ? AND status = 'ACTIVE'")
                .params(timestamp(log.createdAt()), spaceId)
                .update();
        insertLogWithinTransaction(spaceId, log);
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
        // status <> 'DISSOLVED' 守卫：服务层门槛判定与写入之间的窗口内被并发解散时拒改（0 行 → 1006C0002）
        final List<Object> updateParams = new ArrayList<>(params);
        updateParams.add(timestamp(logs.get(0).createdAt()));
        updateParams.add(spaceId);
        final int updatedRows = jdbc.sql("UPDATE space SET " + String.join(", ", sets)
                        + ", updated_at = ? WHERE id = ? AND status <> 'DISSOLVED'")
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
        jdbc.sql("INSERT INTO space_action_log (space_id, target_type, target_id, action, operator, "
                        + "from_value, to_value, result, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(log.spaceId(), log.targetType().name(), log.targetId(), log.action(), log.operator(),
                        log.fromValue(), log.toValue(), log.result().name(), log.reason(),
                        timestamp(log.createdAt()))
                .update();
    }

    private void insertLogWithinTransaction(final long spaceId, final SpaceActionLog log) {
        jdbc.sql("INSERT INTO space_action_log (space_id, target_type, target_id, action, operator, "
                        + "from_value, to_value, result, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(spaceId, log.targetType().name(), log.targetId(), log.action(), log.operator(),
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
