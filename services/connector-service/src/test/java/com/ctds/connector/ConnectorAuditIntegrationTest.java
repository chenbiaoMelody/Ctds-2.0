package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 留痕完整性（hifi §9 T8：申请 / 核验通过 / 核验拒绝三类动作 + 内容不含秘密材料）。 */
class ConnectorAuditIntegrationTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000061";

    @Test
    void applyAndApprovalAreTraced() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final List<String> actions = jdbc.queryForList(
                "SELECT action FROM connector_action_log WHERE connector_no = ? ORDER BY id",
                String.class, connectorNo);
        assertThat(actions).containsExactly("connector.apply", "connector.verify.approved");
        final String result = jdbc.queryForObject(
                "SELECT result FROM connector_action_log WHERE action = 'connector.verify.approved'",
                String.class);
        assertThat(result).isEqualTo("SUCCESS");
    }

    @Test
    void rejectionIsTracedEvenThoughRequestSucceeds() throws Exception {
        final String applicationNo = apply(OWNER, "conn-b");
        stub().respondDid("REVOKED", "did:ctds:S20261026000061.1");
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk());
        final String result = jdbc.queryForObject(
                "SELECT result FROM connector_action_log WHERE action = 'connector.verify.rejected'",
                String.class);
        assertThat(result).isEqualTo("DENIED");
        final String reasonCode = jdbc.queryForObject(
                "SELECT reason_code FROM connector_action_log WHERE action = 'connector.verify.rejected'",
                String.class);
        assertThat(reasonCode).isEqualTo("1010C0007");
    }

    @Test
    void denialIsTracedBeforeRejection() throws Exception {
        stub().respondAdmission("REJECTED");
        mockMvc.perform(post(REGISTRATIONS)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(registrationBody("conn-c")))
                .andExpect(status().isBadRequest());
        final String reasonCode = jdbc.queryForObject(
                "SELECT reason_code FROM connector_action_log WHERE action = 'connector.denied'",
                String.class);
        assertThat(reasonCode).isEqualTo("1010C0006");
    }

    @Test
    void amendAndHeartbeatAreTraced() throws Exception {
        final String applicationNo = apply(OWNER, "conn-e");
        mockMvc.perform(put(REGISTRATIONS + "/" + applicationNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"connectorName\":\"conn-e2\",\"connectorType\":\"STANDARD\","
                                + "\"connectorVersion\":\"1.0.2\"}"))
                .andExpect(status().isOk());
        final String connectorNo = registerConnector(OWNER, "conn-e3");
        mockMvc.perform(post("/api/v1/connectors/" + connectorNo + "/heartbeats")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        final List<String> amendments = jdbc.queryForList(
                "SELECT action FROM connector_action_log WHERE application_no = ? AND action = 'connector.amend'",
                String.class, applicationNo);
        assertThat(amendments).containsExactly("connector.amend");
        final List<String> heartbeats = jdbc.queryForList(
                "SELECT action FROM connector_action_log WHERE connector_no = ? AND action = 'connector.heartbeat'",
                String.class, connectorNo);
        assertThat(heartbeats).containsExactly("connector.heartbeat");
    }

    @Test
    void traceDetailContainsNoCredentialWords() throws Exception {
        registerConnector(OWNER, "conn-d");
        final List<String> details = jdbc.queryForList(
                "SELECT COALESCE(detail, '') FROM connector_action_log", String.class);
        assertThat(details).isNotEmpty();
        assertThat(details).allSatisfy(detail -> {
            final String lower = detail.toLowerCase();
            assertThat(lower).doesNotContain("password").doesNotContain("passwd")
                    .doesNotContain("secret").doesNotContain("token").doesNotContain("apikey")
                    .doesNotContain("privatekey").doesNotContain("credential")
                    .doesNotContain("密码").doesNotContain("口令").doesNotContain("密钥");
        });
    }
}
