package com.ctds.connector.sdk.adapter.gov;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.connector.sdk.spi.SourceCheckFailureType;
import com.ctds.connector.sdk.spi.SourceCheckResult;
import com.ctds.connector.sdk.spi.SourceReadRequest;
import com.ctds.connector.sdk.spi.SourceReadResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 政务共享交换平台适配器单元测试（WBS-3.6.5 hifi §9 U1；规格行为 13 验收 1~4 / 行为 14 验收 1）：
 * 五类 mock 路径结论、read 摘要不含数据本体与凭据、自检只读（源端数据版本不变）、http 不可达映射。
 * 零外网：http 分支只对本地回环不可用端口探测。
 */
class GovExchangeSourceAdapterTest {

    private static final String CREDENTIAL = "gov-exchange-secret-0001";
    private static final String CREDENTIAL_REF = "SC0123456789abcdef0123456789abcdef";
    private static final String CONNECTOR_NO = "CN000001";
    private static final String CONTRACT_NO = "CT0000000001";

    private GovExchangeSourceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new GovExchangeSourceAdapter(ref -> CREDENTIAL, Duration.ofSeconds(2));
        adapter.mockResetCounters();
    }

    private static SourceRegistration registration(final String endpoint) {
        return new SourceRegistration(SourceType.GOV_EXCHANGE, endpoint, CREDENTIAL_REF, CONNECTOR_NO);
    }

    private static SourceReadRequest readRequest() {
        return new SourceReadRequest(CONTRACT_NO, "统计分析");
    }

    @Test
    void adapterDeclaresGovExchangeType() {
        assertThat(adapter.type()).isEqualTo(SourceType.GOV_EXCHANGE);
    }

    @Test
    void mockOkPathPassesCheck() {
        final SourceCheckResult result = adapter.check(registration("mock://gov-exchange/ok"));
        assertThat(result.passed()).isTrue();
        assertThat(result.failureType()).isNull();
        assertThat(result.message()).isNotBlank();
        assertThat(result.message()).doesNotContain(CREDENTIAL);
    }

    @Test
    void mockBadCredentialPathFailsWithInvalidCredential() {
        final SourceCheckResult result = adapter.check(registration("mock://gov-exchange/bad-credential"));
        assertThat(result.passed()).isFalse();
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
        assertThat(result.message()).isEqualTo("凭据无效");
    }

    @Test
    void mockUnreachablePathFailsWithNetworkUnreachable() {
        final SourceCheckResult result = adapter.check(registration("mock://gov-exchange/unreachable"));
        assertThat(result.passed()).isFalse();
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.NETWORK_UNREACHABLE);
        assertThat(result.message()).isEqualTo("网络不可达");
    }

    @Test
    void mockForbiddenPathFailsWithInsufficientPermission() {
        final SourceCheckResult result = adapter.check(registration("mock://gov-exchange/forbidden"));
        assertThat(result.passed()).isFalse();
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.INSUFFICIENT_PERMISSION);
        assertThat(result.message()).isEqualTo("权限不足");
    }

    @Test
    void otherMockPathTreatedAsHealthy() {
        final SourceCheckResult result = adapter.check(registration("mock://gov-exchange/anything-else"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void readReturnsSummaryWithoutDataBodyOrCredential() {
        final SourceReadResult result = adapter.read(registration("mock://gov-exchange/ok"), readRequest());
        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isNotBlank();
        assertThat(result.summary()).doesNotContain(CREDENTIAL);
        assertThat(result.summary()).contains("1 件");
        assertThat(result.summary().length()).isLessThanOrEqualTo(120);
    }

    @Test
    void readFailureCarriesReadableSummary() {
        final SourceReadResult result = adapter.read(
                registration("mock://gov-exchange/bad-credential"), readRequest());
        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("凭据无效");
        assertThat(result.summary()).doesNotContain(CREDENTIAL);
    }

    @Test
    void checkIsReadOnlyForSourceData() {
        adapter.check(registration("mock://gov-exchange/ok"));
        adapter.read(registration("mock://gov-exchange/ok"), readRequest());
        assertThat(adapter.mockExchangeCount()).isPositive();
        assertThat(adapter.mockDataVersion()).isZero();
    }

    @Test
    void unreachableHttpEndpointFailsWithNetworkUnreachable() {
        final SourceCheckResult result = adapter.check(registration("http://127.0.0.1:1/gov-exchange/check"));
        assertThat(result.passed()).isFalse();
        assertThat(result.failureType()).isEqualTo(SourceCheckFailureType.NETWORK_UNREACHABLE);
        assertThat(result.message()).isEqualTo("网络不可达");
    }
}
