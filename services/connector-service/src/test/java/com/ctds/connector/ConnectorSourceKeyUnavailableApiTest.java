package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.crypto.CryptoErrorCodes;
import com.ctds.common.crypto.KeyProvider;
import com.ctds.common.errorcode.BizException;
import com.ctds.connector.support.ConnectorTestSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

/**
 * 凭据保护组件不可用（WBS-3.6.5 hifi §9 T7；规格行为 12 规则 2 fail-closed）：
 * 密钥不可用 → 登记 / 自检 503 1010S0004，且库中无明文残留（禁止明文降级）。
 */
@Import(ConnectorSourceKeyUnavailableApiTest.SwitchableKeyConfig.class)
class ConnectorSourceKeyUnavailableApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";

    @Autowired
    private SwitchableKeyProvider switchableKeyProvider;

    @Test
    void registerFailsClosedWhenKeyUnavailable() throws Exception {
        final String connectorNo = registerConnector(OWNER, "key-register");
        switchableKeyProvider.broken = true;
        try {
            mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Ctds-Subject", OWNER)
                            .header("X-Ctds-Roles", APPLICANT_ROLE)
                            .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("1010S0004"))
                    .andExpect(jsonPath("$.message").value("源凭据保护组件不可用，暂不能登记或自检"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM connector_source", Integer.class)).isZero();
        } finally {
            switchableKeyProvider.broken = false;
        }
    }

    @Test
    void checkFailsClosedWhenKeyUnavailable() throws Exception {
        final String connectorNo = registerConnector(OWNER, "key-check");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        switchableKeyProvider.broken = true;
        try {
            mockMvc.perform(post(CONNECTORS + connectorNo + "/sources/" + sourceNo + "/checks")
                            .header("X-Ctds-Subject", OWNER)
                            .header("X-Ctds-Roles", APPLICANT_ROLE))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("1010S0004"));
            assertThat(govAdapter().mockExchangeCount()).isZero();
        } finally {
            switchableKeyProvider.broken = false;
        }
    }

    @Test
    void detailRemainsReadableWithoutDecryption() throws Exception {
        final String connectorNo = registerConnector(OWNER, "key-detail");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        switchableKeyProvider.broken = true;
        try {
            mockMvc.perform(get(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                            .header("X-Ctds-Subject", OWNER)
                            .header("X-Ctds-Roles", APPLICANT_ROLE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.credentialMasked").value("****0001"));
        } finally {
            switchableKeyProvider.broken = false;
        }
    }

    @Test
    void maskedColumnNeverCarriesPlaintext() throws Exception {
        final String connectorNo = registerConnector(OWNER, "key-mask");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        final String masked = jdbc.queryForObject(
                "SELECT credential_masked FROM connector_source WHERE source_no = ?",
                String.class, sourceNo);
        assertThat(masked).isEqualTo("****0001");
        assertThat(masked).doesNotContain(SOURCE_SECRET);
    }

    /** 可控密钥供给：broken = 模拟 KMS / 密钥文件不可用（fail-closed 路径）。 */
    static final class SwitchableKeyProvider implements KeyProvider {

        private static final byte[] KEY = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);

        private volatile boolean broken;

        @Override
        public byte[] sm4Key(final String keyRef) {
            if (broken) {
                throw new BizException(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE, "密钥服务暂不可用");
            }
            return KEY.clone();
        }
    }

    /** 覆盖组件默认 KeyProvider（@Primary 优先注入到 Sm4Service）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class SwitchableKeyConfig {

        @Bean
        @Primary
        SwitchableKeyProvider switchableKeyProvider() {
            return new SwitchableKeyProvider();
        }
    }
}
