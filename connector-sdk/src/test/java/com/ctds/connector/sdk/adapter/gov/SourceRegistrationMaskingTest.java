package com.ctds.connector.sdk.adapter.gov;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 源登记模型凭据保护单元测试（WBS-3.6.5 hifi §9 U2；规格行为 12 规则 2）：
 * 模型不存在明文凭据字段（编译期即阻断明文路径），toString 对凭据引用掩码（防日志二次泄露）。
 */
class SourceRegistrationMaskingTest {

    private static final String CREDENTIAL_REF = "SC0123456789abcdef0123456789abcdef";

    @Test
    void toStringHidesCredentialRefValue() {
        final SourceRegistration registration = new SourceRegistration(SourceType.GOV_EXCHANGE,
                "mock://gov-exchange/ok", CREDENTIAL_REF, "CN000001");
        final String text = registration.toString();
        assertThat(text).doesNotContain(CREDENTIAL_REF);
        assertThat(text).contains("credentialRef=***");
    }

    @Test
    void modelHasNoPlaintextCredentialField() {
        final RecordComponent[] components = SourceRegistration.class.getRecordComponents();
        assertThat(Arrays.stream(components).map(RecordComponent::getName))
                .containsExactly("sourceType", "endpoint", "credentialRef", "ownerConnectorNo");
    }
}
