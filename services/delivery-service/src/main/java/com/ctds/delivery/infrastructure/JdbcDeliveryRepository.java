package com.ctds.delivery.infrastructure;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.delivery.domain.Delivery;
import com.ctds.delivery.domain.DeliveryActionLog;
import com.ctds.delivery.domain.DeliveryRepository;
import com.ctds.delivery.domain.DeliveryType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 交付记录 JdbcClient 仓储（hifi §2；只读业务要素，不含本体/原文/路径/密钥材料）。 */
@Repository
public class JdbcDeliveryRepository implements DeliveryRepository {

    private static final String COLUMNS = "id, delivery_no, contract_no, product_id, product_name, "
            + "delivery_type, requester_no, provider_no, file_id, file_name, file_size, package_id, "
            + "checksum, key_ref, result, idempotency_key, sandbox_no, result_name, created_at";
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "createdAt", "created_at",
            "created_at", "created_at",
            "deliveryNo", "delivery_no",
            "delivery_no", "delivery_no",
            "deliveryType", "delivery_type",
            "delivery_type", "delivery_type",
            "result", "result");

    private final JdbcClient jdbc;

    public JdbcDeliveryRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Delivery> findByIdempotencyKey(final String requesterNo,
            final String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM delivery WHERE requester_no = :requester "
                        + "AND idempotency_key = :key")
                .param("requester", requesterNo).param("key", idempotencyKey)
                .query(JdbcDeliveryRepository::map).optional();
    }

    @Override
    public Optional<Delivery> findByDeliveryNo(final String deliveryNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM delivery WHERE delivery_no = :no")
                .param("no", deliveryNo).query(JdbcDeliveryRepository::map).optional();
    }

    @Override
    @Transactional
    public String nextDeliveryNo() {
        jdbc.sql("UPDATE delivery_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1")
                .update();
        final Long next = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return String.format("DL%06d", next);
    }

    @Override
    public void insert(final Delivery delivery) {
        jdbc.sql("INSERT INTO delivery (delivery_no, contract_no, product_id, product_name, "
                        + "delivery_type, requester_no, provider_no, file_id, file_name, file_size, "
                        + "package_id, checksum, key_ref, result, idempotency_key, sandbox_no, "
                        + "result_name) VALUES (:deliveryNo, :contractNo, :productId, :productName, "
                        + ":type, :requester, :provider, :fileId, :fileName, :fileSize, :packageId, "
                        + ":checksum, :keyRef, :result, :idempotencyKey, :sandboxNo, :resultName)")
                .param("deliveryNo", delivery.deliveryNo())
                .param("contractNo", delivery.contractNo())
                .param("productId", delivery.productId())
                .param("productName", delivery.productName())
                .param("type", delivery.deliveryType().name())
                .param("requester", delivery.requesterNo())
                .param("provider", delivery.providerNo())
                .param("fileId", delivery.fileId())
                .param("fileName", delivery.fileName())
                .param("fileSize", delivery.fileSize())
                .param("packageId", delivery.packageId())
                .param("checksum", delivery.checksum())
                .param("keyRef", delivery.keyRef())
                .param("result", delivery.result())
                .param("idempotencyKey", delivery.idempotencyKey())
                .param("sandboxNo", delivery.sandboxNo())
                .param("resultName", delivery.resultName())
                .update();
    }

    @Override
    public List<Delivery> listForParticipant(final String callerNo, final String contractNo,
            final DeliveryType type, final PageQuery page) {
        final Map<String, Object> params = new HashMap<>();
        final StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM delivery WHERE "
                + "(requester_no = :caller OR provider_no = :caller)");
        params.put("caller", callerNo);
        appendFilters(sql, params, contractNo, type);
        sql.append(orderClause(page)).append(" LIMIT :limit OFFSET :offset");
        params.put("limit", page.pageSize());
        params.put("offset", page.offset());
        final var spec = jdbc.sql(sql.toString());
        params.forEach(spec::param);
        return spec.query(JdbcDeliveryRepository::map).list();
    }

    @Override
    public long countForParticipant(final String callerNo, final String contractNo,
            final DeliveryType type) {
        final Map<String, Object> params = new HashMap<>();
        final StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM delivery WHERE "
                + "(requester_no = :caller OR provider_no = :caller)");
        params.put("caller", callerNo);
        appendFilters(sql, params, contractNo, type);
        final var spec = jdbc.sql(sql.toString());
        params.forEach(spec::param);
        return spec.query(Long.class).single();
    }

    @Override
    public List<Delivery> listGovernance(final String contractNo, final String deliveryNo,
            final PageQuery page) {
        final Map<String, Object> params = new HashMap<>();
        final StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM delivery WHERE 1 = 1");
        appendGovernanceFilters(sql, params, contractNo, deliveryNo);
        sql.append(orderClause(page)).append(" LIMIT :limit OFFSET :offset");
        params.put("limit", page.pageSize());
        params.put("offset", page.offset());
        final var spec = jdbc.sql(sql.toString());
        params.forEach(spec::param);
        return spec.query(JdbcDeliveryRepository::map).list();
    }

    @Override
    public long countGovernance(final String contractNo, final String deliveryNo) {
        final Map<String, Object> params = new HashMap<>();
        final StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM delivery WHERE 1 = 1");
        appendGovernanceFilters(sql, params, contractNo, deliveryNo);
        final var spec = jdbc.sql(sql.toString());
        params.forEach(spec::param);
        return spec.query(Long.class).single();
    }

    @Override
    public List<Delivery> listBySandbox(final String sandboxNo, final DeliveryType type,
            final PageQuery page) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM delivery WHERE sandbox_no = :sandboxNo "
                        + appendTypeFilter(type) + " ORDER BY id DESC LIMIT :limit OFFSET :offset")
                .param("sandboxNo", sandboxNo)
                .param("type", type == null ? null : type.name())
                .param("limit", page.pageSize())
                .param("offset", page.offset())
                .query(JdbcDeliveryRepository::map).list();
    }

    @Override
    public long countBySandbox(final String sandboxNo, final DeliveryType type) {
        return jdbc.sql("SELECT COUNT(*) FROM delivery WHERE sandbox_no = :sandboxNo "
                        + appendTypeFilter(type))
                .param("sandboxNo", sandboxNo)
                .param("type", type == null ? null : type.name())
                .query(Long.class).single();
    }

    private static String appendTypeFilter(final DeliveryType type) {
        return type == null ? "" : " AND delivery_type = :type";
    }

    @Override
    public void insertActionLog(final DeliveryActionLog log) {
        jdbc.sql("INSERT INTO delivery_action_log (delivery_no, contract_no, operator_no, action, "
                        + "result, reason_code) VALUES (:deliveryNo, :contractNo, :operator, :action, "
                        + ":result, :reason)")
                .param("deliveryNo", log.deliveryNo())
                .param("contractNo", log.contractNo())
                .param("operator", log.operatorNo())
                .param("action", log.action())
                .param("result", log.result())
                .param("reason", log.reasonCode())
                .update();
    }

    @Override
    public boolean existsByContractParticipant(final String contractNo, final String callerNo) {
        return jdbc.sql("SELECT COUNT(*) FROM delivery WHERE contract_no = :contractNo "
                        + "AND (requester_no = :caller OR provider_no = :caller)")
                .param("contractNo", contractNo).param("caller", callerNo)
                .query(Long.class).single() > 0;
    }

    @Override
    public List<DeliveryActionLog> pageActionLogs(final String contractNo, final PageQuery page) {
        return jdbc.sql("SELECT id, delivery_no, contract_no, operator_no, action, result, "
                        + "reason_code, created_at FROM delivery_action_log "
                        + "WHERE contract_no = :contractNo ORDER BY id DESC "
                        + "LIMIT :limit OFFSET :offset")
                .param("contractNo", contractNo)
                .param("limit", page.pageSize())
                .param("offset", page.offset())
                .query(JdbcDeliveryRepository::mapActionLog).list();
    }

    @Override
    public long countActionLogs(final String contractNo) {
        return jdbc.sql("SELECT COUNT(*) FROM delivery_action_log WHERE contract_no = :contractNo")
                .param("contractNo", contractNo).query(Long.class).single();
    }

    private static DeliveryActionLog mapActionLog(final ResultSet rs, final int rowNum)
            throws SQLException {
        return new DeliveryActionLog(rs.getLong("id"), rs.getString("delivery_no"),
                rs.getString("contract_no"), rs.getString("operator_no"), rs.getString("action"),
                rs.getString("result"), rs.getString("reason_code"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private static void appendFilters(final StringBuilder sql, final Map<String, Object> params,
            final String contractNo, final DeliveryType type) {
        if (contractNo != null && !contractNo.isBlank()) {
            sql.append(" AND contract_no = :contractNo");
            params.put("contractNo", contractNo);
        }
        if (type != null) {
            sql.append(" AND delivery_type = :type");
            params.put("type", type.name());
        }
    }

    private static String orderClause(final PageQuery page) {
        if (page.sortField() == null) {
            return " ORDER BY id DESC";
        }
        final String column = SORT_COLUMNS.get(page.sortField());
        if (column == null) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "orderBy 字段不支持");
        }
        final String direction = "desc".equalsIgnoreCase(page.sortDirection()) ? "DESC" : "ASC";
        return "id".equals(column)
                ? " ORDER BY id " + direction
                : " ORDER BY " + column + " " + direction + ", id DESC";
    }

    private static void appendGovernanceFilters(final StringBuilder sql,
            final Map<String, Object> params, final String contractNo, final String deliveryNo) {
        if (contractNo != null && !contractNo.isBlank()) {
            sql.append(" AND contract_no = :contractNo");
            params.put("contractNo", contractNo);
        }
        if (deliveryNo != null && !deliveryNo.isBlank()) {
            sql.append(" AND delivery_no = :deliveryNo");
            params.put("deliveryNo", deliveryNo);
        }
    }

    private static Delivery map(final ResultSet rs, final int rowNum) throws SQLException {
        return new Delivery(rs.getLong("id"), rs.getString("delivery_no"), rs.getString("contract_no"),
                rs.getLong("product_id"), rs.getString("product_name"),
                DeliveryType.valueOf(rs.getString("delivery_type")), rs.getString("requester_no"),
                rs.getString("provider_no"), rs.getString("file_id"), rs.getString("file_name"),
                nullableLong(rs, "file_size"), rs.getString("package_id"), rs.getString("checksum"),
                rs.getString("key_ref"), rs.getString("result"), rs.getString("idempotency_key"),
                rs.getString("sandbox_no"), rs.getString("result_name"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private static Long nullableLong(final ResultSet rs, final String column) throws SQLException {
        final long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
