package com.ctds.connector.sdk.adapter.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.connector.sdk.adapter.gov.CredentialResolver;
import com.ctds.connector.sdk.spi.SourceCheckFailureType;
import com.ctds.connector.sdk.spi.SourceCheckResult;
import com.ctds.connector.sdk.spi.SourceReadRequest;
import com.ctds.connector.sdk.spi.SourceReadResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 数据库适配器（WBS-3.6.6 hifi §9 U1）：只读 {@code SELECT 1} 自检、凭据形态、三类失败映射、
 * 读取摘要只含 schema 与表数（零行数据 / 零表名）、不可归类异常交平台侧 502。
 */
class DatabaseSourceAdapterTest {

    private static final String ENDPOINT = "jdbc:mysql://db.example.com:3306/dataset";
    private static final String CREDENTIAL = "app-user:p@ss:word";

    private static final FakeJdbcDriver DRIVER = new FakeJdbcDriver();
    private final CredentialResolver resolver = ref -> CREDENTIAL;
    private final DatabaseSourceAdapter adapter = new DatabaseSourceAdapter(resolver);

    @BeforeAll
    static void registerDriver() throws SQLException {
        DriverManager.registerDriver(DRIVER);
    }

    @AfterAll
    static void deregisterDriver() throws SQLException {
        DriverManager.deregisterDriver(DRIVER);
    }

    @BeforeEach
    void resetDriver() {
        FakeJdbcDriver.reset();
    }

    private static SourceRegistration registration(final String endpoint) {
        return new SourceRegistration(SourceType.DATABASE, endpoint, "SC-test-ref", "CN000001");
    }

    private static SourceReadRequest readRequest() {
        return new SourceReadRequest("CT0000000001", "统计分析");
    }

    @Test
    void typeIsDatabase() {
        assertThat(adapter.type()).isEqualTo(SourceType.DATABASE);
    }

    @Test
    void checkPassesOnSelectOneAndStaysReadOnly() {
        final SourceCheckResult result = adapter.check(registration(ENDPOINT));

        assertThat(result.passed()).isTrue();
        assertThat(result.failureType()).isNull();
        assertThat(FakeJdbcDriver.readOnlyRequested()).isTrue();
        assertThat(FakeJdbcDriver.writeAttempted()).isFalse();
    }

    @Test
    void invalidCredentialFormatIsBusinessFailure() {
        final DatabaseSourceAdapter broken = new DatabaseSourceAdapter(ref -> "no-colon-credential");

        final SourceCheckResult result = broken.check(registration(ENDPOINT));

        assertThat(result.passed()).isFalse();
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
        assertThat(FakeJdbcDriver.readOnlyRequested()).isFalse();
    }

    @Test
    void emptyUsernameIsBusinessFailure() {
        final DatabaseSourceAdapter broken = new DatabaseSourceAdapter(ref -> ":only-password");

        assertThat(broken.check(registration(ENDPOINT)).failureType())
                .isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
    }

    @Test
    void wrongPasswordMapsToInvalidCredential() {
        FakeJdbcDriver.failOnConnect(new SQLException("access denied", "28000", 1045));

        final SourceCheckResult result = adapter.check(registration(ENDPOINT));

        assertThat(result.passed()).isFalse();
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
        assertThat(result.message()).isEqualTo("凭据无效");
    }

    @Test
    void connectionFailureMapsToNetworkUnreachable() {
        FakeJdbcDriver.failOnConnect(new SQLException("communications link failure", "08S01", 0));

        final SourceCheckResult result = adapter.check(registration(ENDPOINT));

        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.NETWORK_UNREACHABLE);
        assertThat(result.message()).isEqualTo("网络不可达");
    }

    @Test
    void accessDeniedForSchemaMapsToInsufficientPermission() {
        FakeJdbcDriver.failOnConnect(new SQLException("access denied for database", "42000", 1044));

        final SourceCheckResult result = adapter.check(registration(ENDPOINT));

        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INSUFFICIENT_PERMISSION);
        assertThat(result.message()).isEqualTo("权限不足");
    }

    @Test
    void unclassifiedFailureIsNotReportedAsBusinessReason() {
        FakeJdbcDriver.failOnConnect(new SQLException("weird", null, 9999));

        assertThatThrownBy(() -> adapter.check(registration(ENDPOINT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据库探测失败");
    }

    @Test
    void readReturnsSchemaAndTableCountOnly() {
        FakeJdbcDriver.tableCount(12L);

        final SourceReadResult result = adapter.read(registration(ENDPOINT), readRequest());

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isEqualTo("database=dataset;tables=12");
        assertThat(FakeJdbcDriver.lastSchemaParameter()).isEqualTo("dataset");
        assertThat(FakeJdbcDriver.writeAttempted()).isFalse();
    }

    @Test
    void readFailureIsBusinessConclusion() {
        FakeJdbcDriver.failOnConnect(new SQLException("access denied", "28000", 1045));

        final SourceReadResult result = adapter.read(registration(ENDPOINT), readRequest());

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).isEqualTo("读取失败：凭据无效");
    }

    @Test
    void malformedJdbcUrlFailsLoudInsteadOfGuessing() {
        assertThatThrownBy(() -> adapter.check(registration("https://db.example.com/dataset")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据库源地址非法");
    }
}
