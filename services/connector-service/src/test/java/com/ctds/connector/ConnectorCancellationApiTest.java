package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 注销（hifi §9 T3：行为 10 验收 1~3 + 行为 8 规则 1 后半）。 */
class ConnectorCancellationApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000071";
    private static final String OTHER = "S20261026000072";

    @Test
    void cancellationWithoutJobsSucceeds() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final JsonNode lifecycle = cancel(OWNER, APPLICANT_ROLE, connectorNo);

        assertThat(lifecycle.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(lifecycle.path("cancelledAt").isNull()).isFalse();
        assertThat(lifecycle.path("bindingStatus").asText()).isEqualTo("RELEASED");
        assertThat(lifecycle.path("releasedAt").isNull()).isFalse();

        final Long traces = jdbc.queryForObject("SELECT COUNT(*) FROM connector_action_log "
                + "WHERE action = 'connector.cancelled' AND result = 'SUCCESS' AND connector_no = ?",
                Long.class, connectorNo);
        assertThat(traces).isEqualTo(1L);
    }

    @Test
    void cancellationWithInProgressJobRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        occupy(connectorNo, "job-1");

        mockMvc.perform(post(CONNECTORS + connectorNo + "/cancellation")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0008"));

        mockMvc.perform(get(CONNECTORS + connectorNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REGISTERED"));

        final Long denied = jdbc.queryForObject("SELECT COUNT(*) FROM connector_action_log "
                + "WHERE action = 'connector.cancelled' AND result = 'DENIED' AND connector_no = ?",
                Long.class, connectorNo);
        assertThat(denied).isEqualTo(1L);
    }

    @Test
    void cancellationHiddenFromOtherSubjects() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/cancellation")
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void adminCanCancelAnyConnector() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/cancellation")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void repeatedCancellationRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        cancel(OWNER, APPLICANT_ROLE, connectorNo);
        mockMvc.perform(post(CONNECTORS + connectorNo + "/cancellation")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
    }

    @Test
    void canceledConnectorRejectsBusinessActionsButKeepsHistoryReadable() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        cancel(OWNER, APPLICANT_ROLE, connectorNo);

        mockMvc.perform(post(CONNECTORS + connectorNo + "/heartbeats")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
        mockMvc.perform(get(CONNECTORS + connectorNo + "/binding")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
        mockMvc.perform(get(INTERNAL + "connectors/" + connectorNo)
                        .header("X-Ctds-Subject", "gateway-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
        mockMvc.perform(get(CONNECTORS + connectorNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mockMvc.perform(get(CONNECTORS + connectorNo + "/action-logs")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
    }

    @Test
    void pendingUpgradeVoidedOnCancellation() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final String upgradeNo = upgrade(OWNER, APPLICANT_ROLE, connectorNo, "2.0.0", "INCOMPATIBLE")
                .path("upgradeNo").asText();
        cancel(OWNER, APPLICANT_ROLE, connectorNo);

        final String status = jdbc.queryForObject(
                "SELECT status FROM connector_version_change WHERE upgrade_no = ?", String.class, upgradeNo);
        assertThat(status).isEqualTo("VOIDED");
    }

    @Test
    void pendingApplicationWithdrawable() throws Exception {
        final String applicationNo = apply(OWNER, "conn-a");
        mockMvc.perform(delete(REGISTRATIONS + "/" + applicationNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mockMvc.perform(delete(REGISTRATIONS + "/" + applicationNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
    }

    @Test
    void withdrawalHiddenFromOtherSubjects() throws Exception {
        final String applicationNo = apply(OWNER, "conn-a");
        mockMvc.perform(delete(REGISTRATIONS + "/" + applicationNo)
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void registeredApplicationCannotBeWithdrawn() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final String applicationNo = jdbc.queryForObject(
                "SELECT application_no FROM connector_registration WHERE connector_no = ?",
                String.class, connectorNo);
        mockMvc.perform(delete(REGISTRATIONS + "/" + applicationNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
    }

    @Test
    void cancellationBodyNotRequired() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/cancellation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
    }
}
