package com.ctds.connector;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;

/** 绑定与 DID 状态联动（hifi §9 T3：行为 8 验收 1~3，fail-closed）。 */
class ConnectorBindingApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000021";
    private static final String CONNECTORS = "/api/v1/connectors/";

    @Test
    void bindingShowsDidAndBoundTime() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/binding")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectorNo").value(connectorNo))
                .andExpect(jsonPath("$.data.did").value("did:ctds:S20261026000001.1"))
                .andExpect(jsonPath("$.data.bindingStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.didStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.verified").value(true));
    }

    @Test
    void revokedDidMakesBindingUnverified() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-b");
        stub().respondDid("REVOKED", "did:ctds:S20261026000021.1");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/binding")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.didStatus").value("REVOKED"))
                .andExpect(jsonPath("$.data.verified").value(false));
    }

    @Test
    void bindingReturns503WhenDidUnavailable() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-c");
        stub().respondDidUnavailable();
        mockMvc.perform(get(CONNECTORS + connectorNo + "/binding")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("1010S0002"));
    }

    @Test
    void bindingIsTraced() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-d");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/binding")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        final Long traces = jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_action_log WHERE action = 'connector.binding.read' "
                        + "AND connector_no = ?", Long.class, connectorNo);
        org.assertj.core.api.Assertions.assertThat(traces).isEqualTo(1L);
    }
}
