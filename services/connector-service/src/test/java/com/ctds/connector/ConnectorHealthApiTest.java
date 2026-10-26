package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 最小健康度（hifi §9 T4：规格 §7 Q8-A 心跳上报 + 读时派生在线）。 */
class ConnectorHealthApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000031";
    private static final String OTHER = "S20261026000032";
    private static final String CONNECTORS = "/api/v1/connectors/";

    @Test
    void heartbeatMarksConnectorOnline() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/heartbeats")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.online").value(true))
                .andExpect(jsonPath("$.data.lastHeartbeatAt").isNotEmpty());
        mockMvc.perform(get(CONNECTORS + connectorNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.online").value(true));
    }

    @Test
    void heartbeatByOtherSubjectDenied() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-b");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/heartbeats")
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void heartbeatUnknownConnectorDenied() throws Exception {
        mockMvc.perform(post(CONNECTORS + "CN999999/heartbeats")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void staleHeartbeatMarksOffline() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-c");
        jdbc.update("UPDATE connector_registration SET last_heartbeat_at = ? WHERE connector_no = ?",
                LocalDateTime.now().minusMinutes(10), connectorNo);
        mockMvc.perform(get(CONNECTORS + connectorNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.online").value(false));
    }

    @Test
    void neverHeartbeatedConnectorIsOffline() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-d");
        final Boolean online = jdbc.queryForObject(
                "SELECT last_heartbeat_at IS NULL FROM connector_registration WHERE connector_no = ?",
                Boolean.class, connectorNo);
        assertThat(online).isTrue();
        mockMvc.perform(get(CONNECTORS + connectorNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.online").value(false));
    }
}
