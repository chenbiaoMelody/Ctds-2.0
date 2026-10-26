package com.ctds.connector;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** 显式确认、版本链与待确认队列（hifi §9 T2：行为 9 验收 2/4 + Q4）。 */
class ConnectorUpgradeQueryApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000061";
    private static final String OTHER = "S20261026000062";

    @Test
    void confirmationAppliesPendingUpgrade() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final String upgradeNo = upgrade(OWNER, APPLICANT_ROLE, connectorNo, "2.0.0", "INCOMPATIBLE")
                .path("upgradeNo").asText();

        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades/" + upgradeNo + "/confirmation")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.confirmedBy").value(OPERATOR))
                .andExpect(jsonPath("$.data.appliedAt").isNotEmpty());

        mockMvc.perform(get(CONNECTORS + connectorNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectorVersion").value("2.0.0"));

        mockMvc.perform(get("/api/v1/connectors/upgrades/pending")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void confirmationTwiceRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final String upgradeNo = upgrade(OWNER, APPLICANT_ROLE, connectorNo, "2.0.0", "INCOMPATIBLE")
                .path("upgradeNo").asText();
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades/" + upgradeNo + "/confirmation")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades/" + upgradeNo + "/confirmation")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
    }

    @Test
    void confirmationWithMismatchedConnectorRejected() throws Exception {
        final String first = registerConnector(OWNER, "conn-a");
        final String second = registerConnector(OWNER, "conn-b");
        final String upgradeNo = upgrade(OWNER, APPLICANT_ROLE, first, "2.0.0", "INCOMPATIBLE")
                .path("upgradeNo").asText();
        mockMvc.perform(post(CONNECTORS + second + "/upgrades/" + upgradeNo + "/confirmation")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void confirmationRejectedWhenVersionMovedConcurrently() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final String upgradeNo = upgrade(OWNER, APPLICANT_ROLE, connectorNo, "2.0.0", "INCOMPATIBLE")
                .path("upgradeNo").asText();
        jdbc.update("UPDATE connector_registration SET connector_version = '1.9.0' WHERE connector_no = ?",
                connectorNo);
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades/" + upgradeNo + "/confirmation")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
    }

    @Test
    void versionHistoryIsNewestFirst() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        upgrade(OWNER, APPLICANT_ROLE, connectorNo, "1.1.0", "COMPATIBLE");
        upgrade(OWNER, APPLICANT_ROLE, connectorNo, "2.0.0", "INCOMPATIBLE");

        mockMvc.perform(get(CONNECTORS + connectorNo + "/upgrades")
                        .param("pageNum", "1").param("pageSize", "10")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].toVersion").value("2.0.0"))
                .andExpect(jsonPath("$.data.list[0].status").value("PENDING_CONFIRM"))
                .andExpect(jsonPath("$.data.list[0].fromVersion").value("1.1.0"))
                .andExpect(jsonPath("$.data.list[0].compatibility").value("INCOMPATIBLE"))
                .andExpect(jsonPath("$.data.list[0].confirmedBy").value(nullValue()))
                .andExpect(jsonPath("$.data.list[0].appliedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.list[1].toVersion").value("1.1.0"))
                .andExpect(jsonPath("$.data.list[1].status").value("APPLIED"))
                .andExpect(jsonPath("$.data.list[1].fromVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data.list[1].compatibility").value("COMPATIBLE"))
                .andExpect(jsonPath("$.data.list[1].confirmedBy").value(nullValue()))
                .andExpect(jsonPath("$.data.list[1].appliedAt").isNotEmpty());
    }

    @Test
    void confirmationRequiresOperator() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final String upgradeNo = upgrade(OWNER, APPLICANT_ROLE, connectorNo, "2.0.0", "INCOMPATIBLE")
                .path("upgradeNo").asText();
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades/" + upgradeNo + "/confirmation")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }

    @Test
    void pendingQueueRequiresOperator() throws Exception {
        mockMvc.perform(get("/api/v1/connectors/upgrades/pending")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }

    @Test
    void historyHiddenFromOtherSubjects() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/upgrades")
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void paginationOutOfRangeRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/upgrades")
                        .param("pageSize", "101")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
    }

    @Test
    void invalidUpgradeNoRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades/UC12/confirmation")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void adminCanReadHistory() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final String upgradeNo = upgrade(OWNER, APPLICANT_ROLE, connectorNo, "1.1.0", "COMPATIBLE")
                .path("upgradeNo").asText();
        final var result = mockMvc.perform(get(CONNECTORS + connectorNo + "/upgrades")
                        .param("pageNum", "1").param("pageSize", "10")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode list = whole(result).path("data").path("list");
        org.assertj.core.api.Assertions.assertThat(list.get(0).path("upgradeNo").asText())
                .isEqualTo(upgradeNo);
    }
}
