package com.ctds.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.catalog.domain.ActionResult;
import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.common.errorcode.BizException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcDatasetRepositoryDefensiveBranchTest {

    @Test
    void emptyUpdateReturnsWithoutIssuingSql() {
        final AtomicInteger sqlCalls = new AtomicInteger();
        final JdbcDatasetRepository repository = new JdbcDatasetRepository(jdbc(sqlCalls, 1, false));

        repository.updateFields(1L, new DatasetRepository.DatasetUpdate(null, null, null, null),
                List.of());

        assertThat(sqlCalls).hasValue(0);
    }

    @Test
    void duplicateCancellationNameLockIsConvertedToGenericBizException() {
        final AtomicInteger sqlCalls = new AtomicInteger();
        final JdbcDatasetRepository repository = new JdbcDatasetRepository(jdbc(sqlCalls, 1, true));
        final DatasetActionLog log = new DatasetActionLog(null, "owner", 1L, 1L, "CANCEL",
                "ACTIVE", "DELETED", ActionResult.SUCCESS, null,
                LocalDateTime.of(2026, 11, 27, 14, 0));

        assertThatThrownBy(() -> repository.cancel(1L, 1L, "name", log))
                .isInstanceOf(BizException.class)
                .hasMessage("注销名称锁定写入冲突");
        assertThat(sqlCalls).hasValue(2);
    }

    @Test
    void zeroUpdatedRowsOnUpdateThrowsTerminalState() {
        final AtomicInteger sqlCalls = new AtomicInteger();
        final JdbcDatasetRepository repository = new JdbcDatasetRepository(jdbc(sqlCalls, 0, false));

        assertThatThrownBy(() -> repository.updateFields(1L,
                new DatasetRepository.DatasetUpdate("intro", null, null, null), List.of()))
                .isInstanceOf(CatalogBizException.class)
                .hasMessage(CatalogErrorCodes.DATASET_ALREADY_DELETED_MESSAGE);
    }

    @Test
    void zeroUpdatedRowsOnCancelThrowsTerminalState() {
        final AtomicInteger sqlCalls = new AtomicInteger();
        final JdbcDatasetRepository repository = new JdbcDatasetRepository(jdbc(sqlCalls, 0, false));
        final DatasetActionLog log = new DatasetActionLog(null, "owner", 1L, 1L, "CANCEL",
                "ACTIVE", "DELETED", ActionResult.SUCCESS, null,
                LocalDateTime.of(2026, 11, 27, 14, 0));

        assertThatThrownBy(() -> repository.cancel(1L, 1L, "name", log))
                .isInstanceOf(CatalogBizException.class)
                .hasMessage(CatalogErrorCodes.DATASET_ALREADY_DELETED_MESSAGE);
    }

    private static JdbcClient jdbc(final AtomicInteger sqlCalls, final int statusUpdateRows,
            final boolean failNameLockInsert) {
        return (JdbcClient) Proxy.newProxyInstance(JdbcClient.class.getClassLoader(),
                new Class<?>[] {JdbcClient.class}, (proxy, method, args) -> {
                    if ("sql".equals(method.getName())) {
                        sqlCalls.incrementAndGet();
                        return statement((String) args[0], statusUpdateRows, failNameLockInsert);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static JdbcClient.StatementSpec statement(final String sql, final int statusUpdateRows,
            final boolean failNameLockInsert) {
        return (JdbcClient.StatementSpec) Proxy.newProxyInstance(
                JdbcClient.StatementSpec.class.getClassLoader(),
                new Class<?>[] {JdbcClient.StatementSpec.class}, (proxy, method, args) -> {
                    if ("params".equals(method.getName()) || "param".equals(method.getName())) {
                        return proxy;
                    }
                    if ("update".equals(method.getName())) {
                        if (sql.startsWith("UPDATE dataset")) {
                            return statusUpdateRows;
                        }
                        if (sql.contains("INSERT INTO dataset_name_lock") && failNameLockInsert) {
                            throw new DuplicateKeyException("duplicate name lock");
                        }
                    }
                    throw new UnsupportedOperationException(method.getName() + ": " + sql);
                });
    }
}
