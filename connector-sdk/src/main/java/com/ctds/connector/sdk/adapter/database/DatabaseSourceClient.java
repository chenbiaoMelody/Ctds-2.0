package com.ctds.connector.sdk.adapter.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库只读探针（hifi §7.3）：{@code SELECT 1} 自检 + {@code information_schema} 表数摘要。
 *
 * <p>只读语义：连接设 {@code readOnly=true}、{@code autoCommit=true}，语句带查询超时；
 * <b>不执行任何写语句、不执行用户 SQL</b>。驱动由运行宿主提供（SDK 只编译 {@code java.sql}）。</p>
 */
final class DatabaseSourceClient {

    private static final String PROBE_SQL = "SELECT 1";
    private static final String TABLE_COUNT_SQL =
            "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    /** 连通性探针（成功即返回 true；SQLException 交调用方映射三类原因）。 */
    boolean probe(final JdbcUrlPolicy.Target target, final DatabaseCredential credential)
            throws SQLException {
        try (Connection connection = open(target, credential);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery(PROBE_SQL)) {
                return resultSet.next();
            }
        }
    }

    /** schema 级摘要（仅表数；不取表名清单、不取行数据）。 */
    long tableCount(final JdbcUrlPolicy.Target target, final DatabaseCredential credential)
            throws SQLException {
        try (Connection connection = open(target, credential);
             PreparedStatement statement = connection.prepareStatement(TABLE_COUNT_SQL)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, target.schema());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private static Connection open(final JdbcUrlPolicy.Target target,
            final DatabaseCredential credential) throws SQLException {
        final Connection connection = DriverManager.getConnection(target.url(),
                credential.username(), credential.password());
        connection.setReadOnly(true);
        connection.setAutoCommit(true);
        return connection;
    }
}
