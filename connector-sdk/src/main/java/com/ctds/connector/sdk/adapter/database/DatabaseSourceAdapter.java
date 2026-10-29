package com.ctds.connector.sdk.adapter.database;

import com.ctds.connector.sdk.adapter.gov.CredentialResolver;
import com.ctds.connector.sdk.spi.SourceAdapter;
import com.ctds.connector.sdk.spi.SourceCheckFailureType;
import com.ctds.connector.sdk.spi.SourceCheckResult;
import com.ctds.connector.sdk.spi.SourceReadRequest;
import com.ctds.connector.sdk.spi.SourceReadResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/**
 * 数据库适配器（WBS-3.6.6；规格行为 11 规则 1 / 行为 13 / 行为 14）。
 *
 * <p>只读：自检执行 {@code SELECT 1}，读取只取 schema 级摘要（表数）；<b>不执行用户 SQL、不写源端</b>。
 * 凭据 {@code 用户名:密码} 经 {@link CredentialResolver} 于调用期解析，明文不进日志 / 异常 / 摘要。
 * 失败原因按 SQLState / MySQL 错误码映射三类（hifi §7.6）；不可归类异常上抛（平台侧 502 中性文案，
 * 不虚报为网络不可达）。</p>
 */
public final class DatabaseSourceAdapter implements SourceAdapter {

    private static final String MESSAGE_INVALID_CREDENTIAL = "凭据无效";
    private static final String MESSAGE_NETWORK = "网络不可达";
    private static final String MESSAGE_PERMISSION = "权限不足";
    private static final String READ_FAILURE_PREFIX = "读取失败：";
    private static final String CONNECTION_STATE_PREFIX = "08";
    private static final String CREDENTIAL_STATE_PREFIX = "28";
    private static final String PERMISSION_STATE_PREFIX = "42000";
    private static final int MYSQL_ACCESS_DENIED = 1045;
    private static final int MYSQL_DB_ACCESS_DENIED = 1044;
    private static final int MYSQL_TABLE_ACCESS_DENIED = 1142;
    private static final int MYSQL_SPECIFIC_ACCESS_DENIED = 1227;
    private static final String DRIVER_MISSING_MARK = "no suitable driver";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    private final CredentialResolver credentialResolver;
    private final DatabaseSourceClient client;

    public DatabaseSourceAdapter(final CredentialResolver credentialResolver) {
        this(credentialResolver, DEFAULT_TIMEOUT);
    }

    public DatabaseSourceAdapter(final CredentialResolver credentialResolver, final Duration timeout) {
        this.credentialResolver = credentialResolver;
        this.client = new DatabaseSourceClient(timeout);
    }

    @Override
    public SourceType type() {
        return SourceType.DATABASE;
    }

    @Override
    public SourceCheckResult check(final SourceRegistration registration) {
        final Optional<DatabaseCredential> credential = credentialOf(registration);
        if (credential.isEmpty()) {
            return SourceCheckResult.fail(SourceCheckFailureType.INVALID_CREDENTIAL,
                    MESSAGE_INVALID_CREDENTIAL);
        }
        final JdbcUrlPolicy.Target target = targetOf(registration.endpoint());
        try {
            client.probe(target, credential.get());
            return SourceCheckResult.ok();
        } catch (final SQLException e) {
            final SourceCheckFailureType failureType = classify(e);
            if (failureType == null) {
                throw new IllegalStateException("数据库探测失败（未归类异常）", e);
            }
            return SourceCheckResult.fail(failureType, messageOf(failureType));
        }
    }

    @Override
    public SourceReadResult read(final SourceRegistration registration,
            final SourceReadRequest request) {
        final Optional<DatabaseCredential> credential = credentialOf(registration);
        if (credential.isEmpty()) {
            return new SourceReadResult(false, READ_FAILURE_PREFIX + MESSAGE_INVALID_CREDENTIAL);
        }
        final JdbcUrlPolicy.Target target = targetOf(registration.endpoint());
        try {
            final long tables = client.tableCount(target, credential.get());
            return new SourceReadResult(true, "database=" + target.schema() + ";tables=" + tables);
        } catch (final SQLException e) {
            final SourceCheckFailureType failureType = classify(e);
            if (failureType == null) {
                throw new IllegalStateException("数据库读取失败（未归类异常）", e);
            }
            return new SourceReadResult(false, READ_FAILURE_PREFIX + messageOf(failureType));
        }
    }

    private Optional<DatabaseCredential> credentialOf(final SourceRegistration registration) {
        return DatabaseCredential.parse(credentialResolver.resolve(registration.credentialRef()));
    }

    private static JdbcUrlPolicy.Target targetOf(final String endpoint) {
        return JdbcUrlPolicy.parse(endpoint)
                .orElseThrow(() -> new IllegalStateException("数据库源地址非法"));
    }

    /** 三类失败映射（hifi §7.6）；不可归类返回 null（上抛 → 502）。 */
    private static SourceCheckFailureType classify(final SQLException e) {
        final String message = e.getMessage();
        if (message != null && message.toLowerCase().contains(DRIVER_MISSING_MARK)) {
            return null;
        }
        final String state = e.getSQLState();
        final int code = e.getErrorCode();
        if (code == MYSQL_ACCESS_DENIED || (state != null && state.startsWith(CREDENTIAL_STATE_PREFIX))) {
            return SourceCheckFailureType.INVALID_CREDENTIAL;
        }
        if (code == MYSQL_DB_ACCESS_DENIED || code == MYSQL_TABLE_ACCESS_DENIED
                || code == MYSQL_SPECIFIC_ACCESS_DENIED
                || (state != null && state.startsWith(PERMISSION_STATE_PREFIX))) {
            return SourceCheckFailureType.INSUFFICIENT_PERMISSION;
        }
        if (state != null && state.startsWith(CONNECTION_STATE_PREFIX)) {
            return SourceCheckFailureType.NETWORK_UNREACHABLE;
        }
        return null;
    }

    private static String messageOf(final SourceCheckFailureType failureType) {
        return switch (failureType) {
            case INVALID_CREDENTIAL -> MESSAGE_INVALID_CREDENTIAL;
            case INSUFFICIENT_PERMISSION -> MESSAGE_PERMISSION;
            case NETWORK_UNREACHABLE -> MESSAGE_NETWORK;
        };
    }
}
