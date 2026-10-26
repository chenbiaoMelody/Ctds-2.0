package com.ctds.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.connector.sdk.spi.SourceCheckFailureType;
import com.ctds.connector.sdk.spi.SourceCheckResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 适配器 SPI 契约（hifi T14~T15）：四类源枚举齐；三类自检失败原因齐；
 * 源接入登记模型<b>凭据引用制</b>——反射断言字段集，不存在任何明文凭据字段（行为 12 规则 2）。
 */
class SpiContractTest {

    @Test
    void 四类源枚举齐_行为11() {
        assertThat(SourceType.values()).containsExactly(
                SourceType.GOV_EXCHANGE, SourceType.DATABASE, SourceType.OBJECT_STORAGE,
                SourceType.API_GATEWAY);
    }

    @Test
    void 三类自检失败原因可枚举_行为13() {
        assertThat(SourceCheckFailureType.values()).containsExactly(
                SourceCheckFailureType.NETWORK_UNREACHABLE, SourceCheckFailureType.INVALID_CREDENTIAL,
                SourceCheckFailureType.INSUFFICIENT_PERMISSION);
    }

    @Test
    void 源接入登记_凭据引用制_无明文凭据字段_行为12() {
        String[] fields = Arrays.stream(SourceRegistration.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toArray(String[]::new);
        assertThat(fields).containsExactly("sourceType", "endpoint", "credentialRef", "ownerConnectorNo");
    }

    @Test
    void 源接入登记_toString对凭据引用掩码() {
        var registration = new SourceRegistration(SourceType.DATABASE, "jdbc:postgresql://src:5432/db",
                "cred-ref-7f3a", "CN001");
        assertThat(registration.toString())
                .doesNotContain("cred-ref-7f3a")
                .contains("credentialRef=***");
    }

    @Test
    void 自检结果静态工厂语义() {
        assertThat(SourceCheckResult.ok().passed()).isTrue();
        var fail = SourceCheckResult.fail(SourceCheckFailureType.INVALID_CREDENTIAL, "凭据无效");
        assertThat(fail.passed()).isFalse();
        assertThat(fail.failureType()).isEqualTo(SourceCheckFailureType.INVALID_CREDENTIAL);
    }
}
