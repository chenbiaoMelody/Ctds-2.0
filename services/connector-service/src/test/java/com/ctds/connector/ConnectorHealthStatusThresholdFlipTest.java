package com.ctds.connector;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WBS-3.9.4 T6（B6-2 阈值受控构造派生变化，S1-5/S1-6）：同一心跳数据（10 分钟前）在不同
 * offline-after 阈值下派生翻转——默认 5m（ConnectorHealthStatusApiTest）= OFFLINE；
 * 本类注入 15m = ONLINE（双向）；"从未上报"不受阈值影响恒 NEVER_REPORTED。
 * 还原复验 = 默认阈值类同数据用例（S1-6 双向口径）。
 */
class ConnectorHealthStatusThresholdFlipTest extends ConnectorTestSupport {

    @DynamicPropertySource
    static void thresholdProps(final DynamicPropertyRegistry registry) {
        registry.add("ctds.connector.health.offline-after", () -> "15m");
    }

    private static final String OWNER = "S20261026000041";
    private static final String GOVERNANCE = "/api/v1/connectors/governance/connectors";

    @Test
    void sameStaleHeartbeatFlipsToOnlineUnderLooserThreshold() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-flip");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/heartbeats")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        jdbc.update("UPDATE connector_registration SET last_heartbeat_at = ? WHERE connector_no = ?",
                LocalDateTime.now().minusMinutes(10), connectorNo);
        mockMvc.perform(get(GOVERNANCE)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].healthStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data.list[0].online").value(true));
    }

    @Test
    void neverReportedStaysExplicitUnderAnyThreshold() throws Exception {
        registerConnector(OWNER, "conn-nr-flip");
        mockMvc.perform(get(GOVERNANCE)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].healthStatus").value("NEVER_REPORTED"))
                .andExpect(jsonPath("$.data.list[0].online").value(false));
    }
}
