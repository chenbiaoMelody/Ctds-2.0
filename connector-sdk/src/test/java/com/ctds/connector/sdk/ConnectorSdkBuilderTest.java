package com.ctds.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 构建契约（hifi T1 / T16）：必填件缺失 fail-fast（CONFIG 分类）；connectorNo 占位容忍；
 * 构建后六门面可用。
 */
class ConnectorSdkBuilderTest {

    private static final TokenProvider TOKEN = new StaticTokenProvider("demo-token");

    private static final ConnectorContext CONTEXT = new ConnectorContext("S001", "did:ctds:S001", null);

    @Test
    void 缺baseUrl构建即失败_CONFIG分类不静默降级() {
        assertThatThrownBy(() -> ConnectorSdk.builder().tokenProvider(TOKEN).context(CONTEXT).build())
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
    }

    @Test
    void 缺令牌来源构建即失败_CONFIG分类() {
        assertThatThrownBy(() -> ConnectorSdk.builder().baseUrl("http://platform.test").context(CONTEXT).build())
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
    }

    @Test
    void 缺身份锚构建即失败_CONFIG分类() {
        assertThatThrownBy(() -> ConnectorSdk.builder().baseUrl("http://platform.test").tokenProvider(TOKEN).build())
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.sdkErrorType()).isEqualTo(SdkErrorType.CONFIG));
    }

    @Test
    void connectorNo占位null容忍_身份锚两要素必填() {
        assertThat(CONTEXT.connectorNo()).isNull();
        assertThat(CONTEXT.subjectNo()).isEqualTo("S001");
        assertThatThrownBy(() -> new ConnectorContext(null, "did:ctds:S001", null))
                .isInstanceOf(ConnectorException.class);
        assertThatThrownBy(() -> new ConnectorContext("S001", " ", null))
                .isInstanceOf(ConnectorException.class);
    }

    @Test
    void 构建后六门面与身份锚可访问() {
        ConnectorSdk sdk = ConnectorSdk.builder()
                .baseUrl("http://platform.test")
                .tokenProvider(TOKEN)
                .context(CONTEXT)
                .build();
        assertThat(sdk.identity()).isNotNull();
        assertThat(sdk.resource()).isNotNull();
        assertThat(sdk.product()).isNotNull();
        assertThat(sdk.contract()).isNotNull();
        assertThat(sdk.delivery()).isNotNull();
        assertThat(sdk.usageControl()).isNotNull();
        assertThat(sdk.context()).isEqualTo(CONTEXT);
    }
}
