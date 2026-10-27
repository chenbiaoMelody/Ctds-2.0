package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 拒绝不触达源（WBS-3.6.5 hifi §9 T8；规格行为 14 规则 1 可证伪部分）：
 * 未登记 / 跨归属 / 注销后 / 类型未开放四种前置拒绝，适配器零调用（模拟源触达计数 = 0）。
 */
class ConnectorSourceNoTouchTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";
    private static final String OTHER = "S20261026000052";
    private static final String CONTRACT_NO = "CT0000000001";

    private void readExpecting(final String connectorNo, final String sourceNo, final int httpStatus,
            final String code) throws Exception {
        mockMvc.perform(post(INTERNAL + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody(CONTRACT_NO, "统计分析")))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code));
        assertThat(govAdapter().mockExchangeCount()).isZero();
    }

    @Test
    void unregisteredSourceNeverTouchesAdapter() throws Exception {
        final String connectorNo = registerConnector(OWNER, "touch-unregistered");
        readExpecting(connectorNo, "SR999999", 400, "1010C0003");
    }

    @Test
    void crossOwnershipNeverTouchesAdapter() throws Exception {
        final String ownerConnector = registerConnector(OWNER, "touch-a");
        final String otherConnector = registerConnector(OTHER, "touch-b");
        final String sourceNo = registerSource(OWNER, ownerConnector, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        readExpecting(otherConnector, sourceNo, 400, "1010C0003");
    }

    @Test
    void cancelledConnectorNeverTouchesAdapter() throws Exception {
        final String connectorNo = registerConnector(OWNER, "touch-cancelled");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        cancel(OWNER, APPLICANT_ROLE, connectorNo);
        readExpecting(connectorNo, sourceNo, 400, "1010C0004");
    }

    @Test
    void unsupportedTypeNeverTouchesAdapter() throws Exception {
        final String connectorNo = registerConnector(OWNER, "touch-unsupported");
        final String sourceNo = registerSource(OWNER, connectorNo, "DATABASE",
                "jdbc:mysql://db.example.com:3306/dataset", "db-secret-0003");
        readExpecting(connectorNo, sourceNo, 400, "1010C0011");
    }

    @Test
    void successfulReadTouchesAdapterExactlyOnce() throws Exception {
        final String connectorNo = registerConnector(OWNER, "touch-ok");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(INTERNAL + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody(CONTRACT_NO, "统计分析")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
        assertThat(govAdapter().mockExchangeCount()).isEqualTo(1);
    }
}
