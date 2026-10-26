package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.support.ConnectorTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 注册核验（hifi §9 T2：行为 6 验收 4 / 行为 7 验收 1~4）。 */
class ConnectorVerificationApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000011";

    @Test
    void verifyApprovedIssuesConnectorNoAndBinding() throws Exception {
        final String applicationNo = apply(OWNER, "conn-a");
        final org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                        post(REGISTRATIONS + "/" + applicationNo + "/verification")
                                .header("X-Ctds-Subject", OPERATOR)
                                .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.status").value("REGISTERED"))
                .andExpect(jsonPath("$.data.binding.did").value("did:ctds:S20261026000001.1"))
                .andExpect(jsonPath("$.data.binding.verified").value(true))
                .andReturn();
        final String connectorNo = data(result).path("connectorNo").asText();
        assertThat(connectorNo).matches("^CN\\d{6}$");
        final String bindingStatus = jdbc.queryForObject(
                "SELECT status FROM connector_did_binding WHERE connector_no = ?", String.class, connectorNo);
        assertThat(bindingStatus).isEqualTo("ACTIVE");
    }

    @Test
    void verifyRejectsNotAdmittedSubject() throws Exception {
        final String applicationNo = apply(OWNER, "conn-b");
        stub().respondAdmission("REJECTED");
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.failedCheck").value("SUBJECT_ADMISSION"))
                .andExpect(jsonPath("$.data.reasonCode").value("1010C0006"))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM connector_registration WHERE application_no = ?", String.class, applicationNo))
                .isEqualTo("PENDING_REVIEW");
    }

    @Test
    void verifyRejectsRevokedDid() throws Exception {
        final String applicationNo = apply(OWNER, "conn-c");
        stub().respondDid("REVOKED", "did:ctds:S20261026000011.1");
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.failedCheck").value("DID_STATUS"))
                .andExpect(jsonPath("$.data.reasonCode").value("1010C0007"))
                .andExpect(jsonPath("$.data.reason").value(ConnectorErrorCodes.DID_REVOKED_REASON));
        assertThat(jdbc.queryForObject(
                "SELECT last_rejection_code FROM connector_registration WHERE application_no = ?",
                String.class, applicationNo)).isEqualTo("1010C0007");
        assertThat(jdbc.queryForObject(
                "SELECT last_rejection_reason FROM connector_registration WHERE application_no = ?",
                String.class, applicationNo)).isEqualTo(ConnectorErrorCodes.DID_REVOKED_REASON);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM connector_registration WHERE application_no = ?",
                String.class, applicationNo)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void verifyRejectsPendingIssueDid() throws Exception {
        final String applicationNo = apply(OWNER, "conn-j");
        stub().respondDid("PENDING_ISSUE", "did:ctds:S20261026000011.3");
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.failedCheck").value("DID_STATUS"))
                .andExpect(jsonPath("$.data.reasonCode").value("1010C0007"))
                .andExpect(jsonPath("$.data.reason").value(ConnectorErrorCodes.DID_PENDING_REASON));
        assertThat(jdbc.queryForObject(
                "SELECT last_rejection_reason FROM connector_registration WHERE application_no = ?",
                String.class, applicationNo)).isEqualTo(ConnectorErrorCodes.DID_PENDING_REASON);
    }

    @Test
    void verifyRejectsSubjectWithoutDid() throws Exception {
        final String applicationNo = apply(OWNER, "conn-d");
        stub().respondDid("NONE", null);
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.failedCheck").value("DID_STATUS"))
                .andExpect(jsonPath("$.data.reason").value("所属主体尚未签发 DID"));
    }

    @Test
    void verifyReturns503WhenDidServiceUnavailable() throws Exception {
        final String applicationNo = apply(OWNER, "conn-e");
        stub().respondDidUnavailable();
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("1010S0002"));
    }

    @Test
    void verifyNumbersAreUniqueAndSequential() throws Exception {
        final String first = registerConnector(OWNER, "conn-f");
        final String second = registerConnector(OWNER, "conn-g");
        assertThat(first).matches("^CN\\d{6}$");
        assertThat(second).matches("^CN\\d{6}$");
        assertThat(second).isNotEqualTo(first);
        assertThat(Long.parseLong(second.substring(2)))
                .isEqualTo(Long.parseLong(first.substring(2)) + 1);
    }

    @Test
    void verifyAfterAmendSucceedsWithFullTraceChain() throws Exception {
        final String applicationNo = apply(OWNER, "conn-h");
        stub().respondDid("REVOKED", "did:ctds:S20261026000011.1");
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(false));
        mockMvc.perform(put(REGISTRATIONS + "/" + applicationNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"connectorName\":\"conn-h2\",\"connectorType\":\"STANDARD\","
                                + "\"connectorVersion\":\"1.0.1\"}"))
                .andExpect(status().isOk());
        stub().respondDid("ACTIVE", "did:ctds:S20261026000011.2");
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.binding.did").value("did:ctds:S20261026000011.2"));
        final List<String> actions = jdbc.queryForList(
                "SELECT action FROM connector_action_log WHERE application_no = ? ORDER BY id",
                String.class, applicationNo);
        assertThat(actions).containsExactly("connector.apply", "connector.verify.rejected",
                "connector.amend", "connector.verify.approved");
        final String operator = jdbc.queryForObject(
                "SELECT operator_no FROM connector_action_log WHERE action = 'connector.verify.approved'",
                String.class);
        assertThat(operator).isEqualTo(OPERATOR);
    }

    @Test
    void verifyRegisteredAgainRejected() throws Exception {
        final String applicationNo = apply(OWNER, "conn-i");
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
    }
}
