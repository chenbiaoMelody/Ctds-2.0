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
 * 未登记 / 跨归属 / 注销后 / 解析级主机拒绝四种前置拒绝，适配器零调用（模拟源触达计数 = 0）。
 *
 * <p>WBS-3.6.6 改写：原"类型未开放 → {@code 1010C0011}"改为"受限主机 → {@code 1010C0010}"
 * （三类源适配承载已开放，前置拒绝改由调用边界解析级校验承接；属能力开放的必要同步）。</p>
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
    void restrictedHostNeverTouchesAdapter() throws Exception {
        final String connectorNo = registerConnector(OWNER, "touch-restricted");
        final String sourceNo = registerSource(OWNER, connectorNo, "DATABASE",
                "jdbc:mysql://no-such-host.invalid:3306/dataset", "db-user:db-secret-0003");
        readExpecting(connectorNo, sourceNo, 400, "1010C0010");
        // 数据库源无法用政务桩计数判别，改用可判别证据：适配器一旦被调用必写 source.read 留痕
        // （成功 / 业务失败 / 不可归类异常三条路径均留痕），因此"零 source.read 行" = 适配器零调用。
        final Integer reads = jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_action_log WHERE connector_no = ? AND action = ?",
                Integer.class, connectorNo, "source.read");
        assertThat(reads).isZero();
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
