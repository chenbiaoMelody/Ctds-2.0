package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.ActionResult;
import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.catalog.domain.DatasetStatus;
import com.ctds.catalog.domain.DatasetType;
import com.ctds.catalog.domain.DeclareLevel;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL 资源仓储（JdbcClient，ADR-009 迁移规范建表，沿 space SpaceJdbcRepository 先例）。
 * 状态门槛 = UPDATE 带 status='ACTIVE' 前置条件（乐观并发控制，0 行即拒——不先查后改，TOCTOU 防护）；
 * 登记两写与注销两写同事务（hifi §4.1/§4.2）；唯一索引兜底并发创建窗口（DuplicateKeyException → 1007C0001）。
 */
@Repository
public class JdbcDatasetRepository implements DatasetRepository {

    private static final String DATASET_COLUMNS = "id, data_no, space_id, owner_subject_no, name, "
            + "normalized_name, type, intro, semantic_tags, declare_category, declare_level, "
            + "declare_important, status, created_at, updated_at";
    /** 取号键（恒 DATASET；预留 3.3.3~3.3.5 扩展位）。 */
    private static final String SEQ_KEY = "DATASET";
    /** 当日序号上限（6 位；超出按超限错误处理而非溢出编号，沿 subject 先例）。 */
    private static final int MAX_DAILY_SEQ = 999_999;

    private final JdbcClient jdbc;

    public JdbcDatasetRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Dataset> findById(final long datasetId) {
        return jdbc.sql("SELECT " + DATASET_COLUMNS + " FROM dataset WHERE id = ?")
                .param(datasetId)
                .query((rs, rowNum) -> mapDataset(rs))
                .optional();
    }

    @Override
    public boolean existsBySpaceAndNormalizedName(final long spaceId, final String normalizedName) {
        final long count = jdbc.sql("SELECT COUNT(*) FROM dataset WHERE space_id = ? AND normalized_name = ?")
                .params(spaceId, normalizedName)
                .query(Long.class)
                .single();
        return count > 0;
    }

    @Override
    public boolean existsInNameLock(final long spaceId, final String normalizedName) {
        final long count = jdbc.sql("SELECT COUNT(*) FROM dataset_name_lock "
                        + "WHERE space_id = ? AND normalized_name = ?")
                .params(spaceId, normalizedName)
                .query(Long.class)
                .single();
        return count > 0;
    }

    @Override
    public PageResult<Dataset> searchByOwner(final String ownerSubjectNo, final Long spaceId,
            final PageQuery page) {
        final StringBuilder where = new StringBuilder("owner_subject_no = ?");
        final List<Object> params = new ArrayList<>();
        params.add(ownerSubjectNo);
        if (spaceId != null) {
            where.append(" AND space_id = ?");
            params.add(spaceId);
        }
        final String whereSql = where.toString();
        final long total = jdbc.sql("SELECT COUNT(*) FROM dataset WHERE " + whereSql)
                .params(params)
                .query(Long.class)
                .single();
        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.pageSize());
        pageParams.add(page.offset());
        final List<Dataset> list = jdbc.sql("SELECT " + DATASET_COLUMNS + " FROM dataset WHERE " + whereSql
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> mapDataset(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    @Override
    public PageResult<DatasetActionLog> pageLogsByDataset(final long datasetId, final PageQuery page) {
        // 只读既有 dataset_action_log（WBS-3.3.6 R14；按 created_at DESC, id DESC 稳定排序）
        final long total = jdbc.sql("SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ?")
                .param(datasetId)
                .query(Long.class)
                .single();
        final List<DatasetActionLog> list = jdbc.sql("SELECT id, actor_subject_no, space_id, dataset_id, "
                        + "action, from_value, to_value, result, reason_code, created_at "
                        + "FROM dataset_action_log WHERE dataset_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .param(datasetId)
                .param(page.pageSize())
                .param(page.offset())
                .query((rs, rowNum) -> mapActionLog(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    private static DatasetActionLog mapActionLog(final ResultSet rs) throws SQLException {
        return new DatasetActionLog(rs.getLong("id"), rs.getString("actor_subject_no"),
                rs.getLong("space_id"), rs.getLong("dataset_id"), rs.getString("action"),
                rs.getString("from_value"), rs.getString("to_value"),
                ActionResult.valueOf(rs.getString("result")), rs.getString("reason_code"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    @Override
    @Transactional
    public long create(final Dataset dataset, final DatasetActionLog log) {
        try {
            jdbc.sql("INSERT INTO dataset (data_no, space_id, owner_subject_no, name, normalized_name, type, "
                            + "intro, semantic_tags, declare_category, declare_level, declare_important, "
                            + "status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(dataset.dataNo(), dataset.spaceId(), dataset.ownerSubjectNo(), dataset.name(),
                            dataset.normalizedName(), dataset.type().name(), dataset.intro(),
                            dataset.semanticTagsJson(), dataset.declareCategory(),
                            dataset.declareLevel().name(), dataset.declareImportant() ? 1 : 0,
                            dataset.status().name(), timestamp(dataset.createdAt()),
                            timestamp(dataset.updatedAt()))
                    .update();
        } catch (final DuplicateKeyException e) {
            // uk_space_norm_name 兜底并发登记窗口（与领域服务层预检构成双保险，沿 space 先例）
            throw new CatalogBizException(CatalogErrorCodes.DATASET_NAME_DUPLICATED,
                    CatalogErrorCodes.DATASET_NAME_DUPLICATED_MESSAGE);
        }
        final long id = jdbc.sql("SELECT id FROM dataset WHERE data_no = ?")
                .param(dataset.dataNo())
                .query(Long.class)
                .single();
        insertLogWithinTransaction(withDatasetId(log, id));
        return id;
    }

    @Override
    @Transactional
    public void updateFields(final long datasetId, final DatasetUpdate update,
            final List<DatasetActionLog> logs) {
        final List<String> sets = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        if (update.intro() != null) {
            sets.add("intro = ?");
            params.add(update.intro());
        }
        if (update.semanticTagsJson() != null) {
            sets.add("semantic_tags = ?");
            params.add(update.semanticTagsJson());
        }
        if (update.declareCategory() != null) {
            sets.add("declare_category = ?");
            params.add(update.declareCategory());
        }
        if (update.declareLevel() != null) {
            sets.add("declare_level = ?");
            params.add(update.declareLevel().name());
        }
        if (sets.isEmpty()) {
            // 无实际变更字段：调用方（应用服务）已拒空变更请求；此处防御性直返（不产生空 UPDATE）
            return;
        }
        sets.add("updated_at = ?");
        params.add(timestamp(logs.isEmpty() ? LocalDateTime.now() : logs.get(0).createdAt()));
        params.add(datasetId);
        final int updatedRows = jdbc.sql("UPDATE dataset SET " + String.join(", ", sets)
                        + " WHERE id = ? AND status = ?")
                .params(params)
                .param(DatasetStatus.ACTIVE.name())
                .update();
        if (updatedRows == 0) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_ALREADY_DELETED,
                    CatalogErrorCodes.DATASET_ALREADY_DELETED_MESSAGE);
        }
        for (final DatasetActionLog log : logs) {
            insertLogWithinTransaction(withDatasetId(log, datasetId));
        }
    }

    @Override
    @Transactional
    public void cancel(final long datasetId, final long spaceId, final String normalizedName,
            final DatasetActionLog log) {
        // 两写①：状态乐观门槛更新（WHERE status='ACTIVE'；0 行 = 已注销/并发收紧 → 1007C0007）
        final int updatedRows = jdbc.sql("UPDATE dataset SET status = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ?")
                .params(DatasetStatus.DELETED.name(), timestamp(log.createdAt()), datasetId,
                        DatasetStatus.ACTIVE.name())
                .update();
        if (updatedRows == 0) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_ALREADY_DELETED,
                    CatalogErrorCodes.DATASET_ALREADY_DELETED_MESSAGE);
        }
        // 两写②：同空间名称锁定（行为 2 规则 3）；PK 冲突理论不可达（uk_space_norm_name 已挡），
        // 防御性分支：事务回滚 + 500 通用码（沿 3.2.3 防御性分支先例）
        try {
            jdbc.sql("INSERT INTO dataset_name_lock (space_id, normalized_name, dataset_id, locked_at) "
                            + "VALUES (?, ?, ?, ?)")
                    .params(spaceId, normalizedName, datasetId, timestamp(log.createdAt()))
                    .update();
        } catch (final DuplicateKeyException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "注销名称锁定写入冲突");
        }
        insertLogWithinTransaction(withDatasetId(log, datasetId));
    }

    @Override
    public void insertLog(final DatasetActionLog log) {
        insertLogWithinTransaction(log);
    }

    /**
     * 当日序号原子取号：LAST_INSERT_ID(expr) 在自增的同时写入连接级返回值，自增与读取不跨语句竞态
     * （沿 subject nextDailySeq 先例）；事务绑定保证两条语句共用同一连接（LAST_INSERT_ID 为连接级）。
     * 新插入路径写 LAST_INSERT_ID(1)（首次序号 = 1）；重复键走 UPDATE 路径写 LAST_INSERT_ID(seq_value + 1)。
     */
    @Override
    @Transactional
    public int nextDailySeq(final LocalDate date) {
        jdbc.sql("INSERT INTO dataset_no_seq (seq_date, seq_key, seq_value) VALUES (?, ?, LAST_INSERT_ID(1)) "
                        + "ON DUPLICATE KEY UPDATE seq_value = LAST_INSERT_ID(seq_value + 1)")
                .params(Date.valueOf(date), SEQ_KEY)
                .update();
        final int seq = jdbc.sql("SELECT LAST_INSERT_ID()")
                .query(Integer.class)
                .single();
        if (seq > MAX_DAILY_SEQ) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "当日数据标识序号已达上限");
        }
        return seq;
    }

    // ==== 内部 ====

    /** 留痕行写入（事务内共用；只插不改）。 */
    private void insertLogWithinTransaction(final DatasetActionLog log) {
        jdbc.sql("INSERT INTO dataset_action_log (actor_subject_no, space_id, dataset_id, action, "
                        + "from_value, to_value, result, reason_code, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(log.actorSubjectNo(), log.spaceId(), log.datasetId(), log.action(),
                        log.fromValue(), log.toValue(), log.result().name(), log.reasonCode(),
                        timestamp(log.createdAt()))
                .update();
    }

    /** 留痕行回填资源 id（登记场景写入前未知，落库前统一以实际主键回填）。 */
    private static DatasetActionLog withDatasetId(final DatasetActionLog log, final long datasetId) {
        return new DatasetActionLog(log.id(), log.actorSubjectNo(), log.spaceId(), datasetId,
                log.action(), log.fromValue(), log.toValue(), log.result(), log.reasonCode(),
                log.createdAt());
    }

    private static Timestamp timestamp(final LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private Dataset mapDataset(final ResultSet rs) throws SQLException {
        return new Dataset(
                rs.getLong("id"),
                rs.getString("data_no"),
                rs.getLong("space_id"),
                rs.getString("owner_subject_no"),
                rs.getString("name"),
                rs.getString("normalized_name"),
                DatasetType.valueOf(rs.getString("type")),
                rs.getString("intro"),
                rs.getString("semantic_tags"),
                rs.getString("declare_category"),
                DeclareLevel.valueOf(rs.getString("declare_level")),
                rs.getInt("declare_important") == 1,
                DatasetStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
