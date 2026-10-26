package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;

/** 生命周期聚合读与注销后重注册（hifi §9 T4：行为 10 验收 4）。 */
class ConnectorLifecycleApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000081";
    private static final String OTHER = "S20261026000082";

    @Test
    void lifecycleOfRegisteredConnector() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/lifecycle")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectorNo").value(connectorNo))
                .andExpect(jsonPath("$.data.status").value("REGISTERED"))
                .andExpect(jsonPath("$.data.connectorVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data.bindingStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.cancelledAt").value(nullValue()))
                .andExpect(jsonPath("$.data.inProgressJobs").value(0))
                .andExpect(jsonPath("$.data.pendingUpgrade").value(false))
                .andExpect(jsonPath("$.data.versionHistory.length()").value(0))
                .andExpect(jsonPath("$.data.online").value(false));
    }

    @Test
    void lifecycleAfterCancellationKeepsHistory() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        upgrade(OWNER, APPLICANT_ROLE, connectorNo, "1.1.0", "COMPATIBLE");
        cancel(OWNER, APPLICANT_ROLE, connectorNo);

        mockMvc.perform(get(CONNECTORS + connectorNo + "/lifecycle")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.data.bindingStatus").value("RELEASED"))
                .andExpect(jsonPath("$.data.releasedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.connectorVersion").value("1.1.0"))
                .andExpect(jsonPath("$.data.versionHistory.length()").value(1))
                .andExpect(jsonPath("$.data.versionHistory[0].toVersion").value("1.1.0"));
    }

    @Test
    void lifecycleShowsInProgressJobs() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        occupy(connectorNo, "job-1");
        occupy(connectorNo, "job-2");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/lifecycle")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inProgressJobs").value(2));
    }

    @Test
    void lifecycleShowsPendingUpgradeFlag() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        upgrade(OWNER, APPLICANT_ROLE, connectorNo, "2.0.0", "INCOMPATIBLE");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/lifecycle")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingUpgrade").value(true));
    }

    @Test
    void lifecycleHiddenFromOtherSubjects() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/lifecycle")
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
        mockMvc.perform(get(CONNECTORS + connectorNo + "/lifecycle")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk());
    }

    @Test
    void lifecycleRejectsInvalidConnectorNo() throws Exception {
        mockMvc.perform(get(CONNECTORS + "CN12" + "/lifecycle")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void reregistrationAfterCancellationGetsNewNumber() throws Exception {
        final String oldConnectorNo = registerConnector(OWNER, "conn-a");
        cancel(OWNER, APPLICANT_ROLE, oldConnectorNo);

        final String newConnectorNo = registerConnector(OWNER, "conn-a");
        assertThat(newConnectorNo).isNotEqualTo(oldConnectorNo);

        mockMvc.perform(get(CONNECTORS + oldConnectorNo + "/lifecycle")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mockMvc.perform(get(CONNECTORS + newConnectorNo + "/lifecycle")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REGISTERED"));
    }
}
