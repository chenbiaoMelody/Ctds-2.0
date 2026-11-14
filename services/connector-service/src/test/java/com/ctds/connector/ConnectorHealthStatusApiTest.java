package com.ctds.connector;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.4 T5/T6（B6-1 三态显式，S1-4~S1-6）：连接器健康度三态加性字段 `healthStatus`
 * （ONLINE / OFFLINE / NEVER_REPORTED）——"从未上报"不再以空值冒充（B6-R1），
 * 既有 `online` 布尔零破坏（加性回归同断言）。默认阈值 5m（ctds.connector.health.offline-after）。
 */
class ConnectorHealthStatusApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000041";
    private static final String GOVERNANCE = "/api/v1/connectors/governance/connectors";

    @Test
    void neverReportedIsExplicitNotSilent() throws Exception {
        registerConnector(OWNER, "conn-nr");
        mockMvc.perform(get(GOVERNANCE)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].healthStatus").value("NEVER_REPORTED"))
                .andExpect(jsonPath("$.data.list[0].online").value(false))
                .andExpect(jsonPath("$.data.list[0].lastHeartbeatAt").value((String) null));
    }

    @Test
    void freshHeartbeatDerivesOnline() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-on");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/heartbeats")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.healthStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data.online").value(true));
        mockMvc.perform(get(GOVERNANCE)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].healthStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data.list[0].online").value(true));
    }

    @Test
    void staleHeartbeatBeyondDefaultThresholdDerivesOffline() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-off");
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
                .andExpect(jsonPath("$.data.list[0].healthStatus").value("OFFLINE"))
                // 加性回归：既有 online 布尔语义零变化
                .andExpect(jsonPath("$.data.list[0].online").value(false));
    }

    @Test
    void offlineConnectorStaysRegistered() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-keep");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/heartbeats")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        jdbc.update("UPDATE connector_registration SET last_heartbeat_at = ? WHERE connector_no = ?",
                LocalDateTime.now().minusMinutes(30), connectorNo);
        mockMvc.perform(get(GOVERNANCE)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].healthStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.data.list[0].status").value("REGISTERED"));
    }
}
