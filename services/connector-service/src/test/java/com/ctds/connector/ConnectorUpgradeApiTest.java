package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** 升级登记与兼容分级（hifi §9 T1：行为 9 验收 1~3）。 */
class ConnectorUpgradeApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";
    private static final String OTHER = "S20261026000052";

    @Test
    void compatibleUpgradeReplacesVersionAtomically() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final JsonNode change = upgrade(OWNER, APPLICANT_ROLE, connectorNo, "1.1.0", "COMPATIBLE");

        assertThat(change.path("upgradeNo").asText()).startsWith("UC");
        assertThat(change.path("fromVersion").asText()).isEqualTo("1.0.0");
        assertThat(change.path("toVersion").asText()).isEqualTo("1.1.0");
        assertThat(change.path("status").asText()).isEqualTo("APPLIED");
        assertThat(change.path("appliedAt").isNull()).isFalse();
        assertThat(change.path("confirmedBy").isNull()).isTrue();

        mockMvc.perform(get(CONNECTORS + connectorNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectorVersion").value("1.1.0"))
                .andExpect(jsonPath("$.data.status").value("REGISTERED"));
    }

    @Test
    void compatibleUpgradeKeepsBusinessActionsWorking() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        upgrade(OWNER, APPLICANT_ROLE, connectorNo, "1.1.0", "COMPATIBLE");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/heartbeats")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
    }

    @Test
    void incompatibleUpgradeWaitsForConfirmation() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final JsonNode change = upgrade(OWNER, APPLICANT_ROLE, connectorNo, "2.0.0", "INCOMPATIBLE");

        assertThat(change.path("status").asText()).isEqualTo("PENDING_CONFIRM");
        assertThat(change.path("appliedAt").isNull()).isTrue();
        mockMvc.perform(get(CONNECTORS + connectorNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectorVersion").value("1.0.0"));
    }

    @Test
    void secondPendingUpgradeRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        upgrade(OWNER, APPLICANT_ROLE, connectorNo, "2.0.0", "INCOMPATIBLE");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"toVersion\":\"3.0.0\",\"compatibility\":\"INCOMPATIBLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
    }

    @Test
    void sameVersionRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"toVersion\":\"1.0.0\",\"compatibility\":\"COMPATIBLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0009"));
    }

    @Test
    void invalidVersionFormatRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"toVersion\":\"1.0\",\"compatibility\":\"COMPATIBLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void invalidCompatibilityRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"toVersion\":\"1.2.0\",\"compatibility\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void upgradeHiddenFromOtherSubjects() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"toVersion\":\"1.1.0\",\"compatibility\":\"COMPATIBLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void canceledConnectorRejectsUpgrade() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        cancel(OWNER, APPLICANT_ROLE, connectorNo);
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"toVersion\":\"1.1.0\",\"compatibility\":\"COMPATIBLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
    }

    @Test
    void invalidConnectorNoRejected() throws Exception {
        mockMvc.perform(post(CONNECTORS + "CN12" + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"toVersion\":\"1.1.0\",\"compatibility\":\"COMPATIBLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void unknownConnectorRejected() throws Exception {
        mockMvc.perform(post(CONNECTORS + "CN999999" + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"toVersion\":\"1.1.0\",\"compatibility\":\"COMPATIBLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void upgradeIsTraced() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final MvcResult result = mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"toVersion\":\"1.1.0\",\"compatibility\":\"COMPATIBLE\"}"))
                .andExpect(status().isOk())
                .andReturn();
        final Long traces = jdbc.queryForObject("SELECT COUNT(*) FROM connector_action_log "
                + "WHERE action = 'connector.upgrade.applied' AND connector_no = ? "
                + "AND detail LIKE '%from=1.0.0%to=1.1.0%'", Long.class, connectorNo);
        assertThat(traces).isEqualTo(1L);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }
}
