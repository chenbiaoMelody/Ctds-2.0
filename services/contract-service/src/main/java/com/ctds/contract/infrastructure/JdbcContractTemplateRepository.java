package com.ctds.contract.infrastructure;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.contract.domain.ContractTemplate;
import com.ctds.contract.domain.ContractTemplateRepository;
import com.ctds.contract.domain.TemplateAction;
import com.ctds.contract.domain.TemplateActionLog;
import com.ctds.contract.domain.TemplateStatus;
import com.ctds.contract.domain.TemplateType;
import com.ctds.contract.domain.TemplateVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合约模板仓储（JdbcClient，ADR-009 迁移规范建表 V1；沿 catalog JdbcDatasetRepository 先例）。
 * 多写事务口径（hifi §6）：create = 取号+三写、revise = 三写、updateStatus = 两写，
 * 事务边界在本仓储方法（@Transactional）；insertLog 为独立写入（拒绝留痕，无事务包裹需求）。
 * <b>无任何更新版本行的方法</b>——版本行不可变（编译期保证，hifi §6）。
 */
@Repository
public class JdbcContractTemplateRepository implements ContractTemplateRepository {

    private static final String TEMPLATE_COLUMNS = "id, template_no, template_name, template_type, "
            + "current_version, status, created_by, created_at, updated_at";
    /** 版本表列（JOIN 场景统一带 v. 前缀——contract_template 同名 id 列歧义防护）。 */
    private static final String VERSION_COLUMNS = "v.id, v.template_id, v.version_no, "
            + "v.clause_framework, v.published_by, v.published_at";
    private static final String LOG_COLUMNS =
            "id, template_no, version_no, action, actor_subject_no, reason_code, from_value, to_value, created_at";

    private final JdbcClient jdbc;

    public JdbcContractTemplateRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public long create(final ContractTemplate template, final TemplateVersion version,
            final TemplateActionLog log) {
        jdbc.sql("INSERT INTO contract_template (template_no, template_name, "
                        + "template_type, current_version, status, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")
                .param(template.templateNo()).param(template.name()).param(template.type().name())
                .param(template.currentVersion()).param(template.status().name())
                .param(template.createdBy()).update();
        // templateNo 全局唯一（uk_template_no），回查 id 供版本表外键引用（模板编号业务键语义）
        final long templateId = jdbc.sql("SELECT id FROM contract_template WHERE template_no = ?")
                .param(template.templateNo()).query(Long.class).single();
        jdbc.sql("INSERT INTO contract_template_version (template_id, version_no, clause_framework, "
                        + "published_by) VALUES (?, ?, ?, ?)")
                .param(templateId).param(version.versionNo()).param(version.clauseFrameworkJson())
                .param(version.publishedBy()).update();
        insertLog(log);
        return templateId;
    }

    @Override
    public Optional<ContractTemplate> findByNo(final String templateNo) {
        return jdbc.sql("SELECT " + TEMPLATE_COLUMNS + " FROM contract_template WHERE template_no = ?")
                .param(templateNo)
                .query((rs, rowNum) -> mapTemplate(rs))
                .optional();
    }

    @Override
    public Optional<TemplateVersion> findVersion(final String templateNo, final int versionNo) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM contract_template_version v "
                        + "JOIN contract_template t ON t.id = v.template_id WHERE t.template_no = ? "
                        + "AND v.version_no = ?")
                .param(templateNo).param(versionNo)
                .query((rs, rowNum) -> mapVersion(rs))
                .optional();
    }

    @Override
    public List<TemplateVersion> listVersions(final String templateNo) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM contract_template_version v "
                        + "JOIN contract_template t ON t.id = v.template_id WHERE t.template_no = ? "
                        + "ORDER BY v.version_no ASC")
                .param(templateNo)
                .query((rs, rowNum) -> mapVersion(rs))
                .list();
    }

    @Override
    @Transactional
    public void revise(final TemplateVersion newVersion, final TemplateActionLog log) {
        jdbc.sql("INSERT INTO contract_template_version (template_id, version_no, clause_framework, "
                        + "published_by) VALUES (?, ?, ?, ?)")
                .param(newVersion.templateId()).param(newVersion.versionNo())
                .param(newVersion.clauseFrameworkJson()).param(newVersion.publishedBy()).update();
        jdbc.sql("UPDATE contract_template SET current_version = ? WHERE id = ?")
                .param(newVersion.versionNo()).param(newVersion.templateId()).update();
        insertLog(log);
    }

    @Override
    @Transactional
    public void updateStatus(final ContractTemplate template, final TemplateStatus target,
            final TemplateActionLog log) {
        jdbc.sql("UPDATE contract_template SET status = ? WHERE id = ?")
                .param(target.name()).param(template.id()).update();
        insertLog(log);
    }

    @Override
    public void insertLog(final TemplateActionLog log) {
        jdbc.sql("INSERT INTO contract_template_action_log (template_no, version_no, action, "
                        + "actor_subject_no, reason_code, from_value, to_value) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .param(log.templateNo()).param(log.versionNo()).param(log.action().name())
                .param(log.actorSubjectNo()).param(log.reasonCode())
                .param(log.fromValue()).param(log.toValue()).update();
    }

    @Override
    public PageResult<ContractTemplate> pageEnabled(final TemplateType type, final PageQuery page) {
        final StringBuilder where = new StringBuilder("status = 'ENABLED'");
        final List<Object> params = new ArrayList<>();
        if (type != null) {
            where.append(" AND template_type = ?");
            params.add(type.name());
        }
        return pageTemplates(where.toString(), params, page);
    }

    @Override
    public PageResult<ContractTemplate> pageManage(final TemplateStatus status, final TemplateType type,
            final PageQuery page) {
        final StringBuilder where = new StringBuilder("1 = 1");
        final List<Object> params = new ArrayList<>();
        if (status != null) {
            where.append(" AND status = ?");
            params.add(status.name());
        }
        if (type != null) {
            where.append(" AND template_type = ?");
            params.add(type.name());
        }
        return pageTemplates(where.toString(), params, page);
    }

    @Override
    public PageResult<TemplateActionLog> pageLogs(final String templateNo, final PageQuery page) {
        final StringBuilder where = new StringBuilder("1 = 1");
        final List<Object> params = new ArrayList<>();
        if (templateNo != null && !templateNo.isBlank()) {
            where.append(" AND template_no = ?");
            params.add(templateNo);
        }
        final long total = jdbc.sql("SELECT COUNT(*) FROM contract_template_action_log WHERE " + where)
                .params(params).query(Long.class).single();
        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.pageSize());
        pageParams.add(page.offset());
        final List<TemplateActionLog> list = jdbc.sql("SELECT " + LOG_COLUMNS
                        + " FROM contract_template_action_log WHERE " + where
                        + " ORDER BY id DESC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> mapLog(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    @Override
    public boolean existsByTypeAndNormalizedName(final TemplateType type, final String normalizedName) {
        return jdbc.sql("SELECT COUNT(*) FROM contract_template WHERE template_type = ? "
                        + "AND template_name_norm = ?")
                .param(type.name()).param(normalizedName)
                .query(Long.class).single() > 0;
    }

    @Override
    @Transactional
    public int nextTemplateNoSeq() {
        // 全局 1 行原子自增（LAST_INSERT_ID 连接级技巧，沿 subject nextDailySeq 先例）；
        // 事务绑定保证两条语句共用同一连接（LAST_INSERT_ID 为连接级，脱离事务则跨连接读到 0/残留值）
        jdbc.sql("UPDATE contract_template_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1")
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Integer.class).single();
    }

    // ==== 内部：分页与行映射 ====

    private PageResult<ContractTemplate> pageTemplates(final String where, final List<Object> params,
            final PageQuery page) {
        final long total = jdbc.sql("SELECT COUNT(*) FROM contract_template WHERE " + where)
                .params(params).query(Long.class).single();
        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.pageSize());
        pageParams.add(page.offset());
        final List<ContractTemplate> list = jdbc.sql("SELECT " + TEMPLATE_COLUMNS
                        + " FROM contract_template WHERE " + where
                        + " ORDER BY id ASC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> mapTemplate(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    private ContractTemplate mapTemplate(final ResultSet rs) throws SQLException {
        return new ContractTemplate(rs.getLong("id"), rs.getString("template_no"),
                rs.getString("template_name"), TemplateType.valueOf(rs.getString("template_type")),
                rs.getInt("current_version"), TemplateStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"), toLocalDateTime(rs, "created_at"),
                toLocalDateTime(rs, "updated_at"));
    }

    private TemplateVersion mapVersion(final ResultSet rs) throws SQLException {
        return new TemplateVersion(rs.getLong("id"), rs.getLong("template_id"), rs.getInt("version_no"),
                rs.getString("clause_framework"), rs.getString("published_by"),
                toLocalDateTime(rs, "published_at"));
    }

    private TemplateActionLog mapLog(final ResultSet rs) throws SQLException {
        final int versionNo = rs.getInt("version_no");
        return new TemplateActionLog(rs.getLong("id"), rs.getString("template_no"),
                rs.wasNull() ? null : versionNo, TemplateAction.valueOf(rs.getString("action")),
                rs.getString("actor_subject_no"), rs.getString("reason_code"),
                rs.getString("from_value"), rs.getString("to_value"),
                toLocalDateTime(rs, "created_at"));
    }

    private static LocalDateTime toLocalDateTime(final ResultSet rs, final String column)
            throws SQLException {
        final Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
