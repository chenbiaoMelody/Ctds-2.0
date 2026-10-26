package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 注册申请与补正（hifi §9 T1：行为 1 验收 1~4 / 行为 6 验收 1~3 / 行为 7 验收 4）。 */
class ConnectorRegistrationApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000001";
    private static final String OTHER = "S20261026000002";

    @Test
    void applyCreatesPendingReviewWithApplicationNo() throws Exception {
        final org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(post(REGISTRATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(registrationBody("conn-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.subjectNo").value(OWNER))
                .andExpect(jsonPath("$.data.connectorName").value("conn-a"))
                .andReturn();
        assertThat(data(result).path("applicationNo").asText()).matches("^CA\\d{6}$");
    }

    @Test
    void applyRejectsNotAdmittedSubject() throws Exception {
        stub().respondAdmission("PENDING_REVIEW");
        mockMvc.perform(post(REGISTRATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(registrationBody("conn-b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0006"));
        assertThat(count("connector_registration")).isZero();
    }

    @Test
    void applyRejectsSubjectThatDoesNotExist() throws Exception {
        stub().respondAdmissionNotFound();
        mockMvc.perform(post(REGISTRATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(registrationBody("conn-b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0006"));
    }

    @Test
    void applyRejectsDuplicateNameWithoutNewRecord() throws Exception {
        apply(OWNER, "conn-dup");
        mockMvc.perform(post(REGISTRATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(registrationBody("CONN-DUP")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0002"));
        assertThat(count("connector_registration")).isEqualTo(1);
    }

    @Test
    void applyRejectsInvalidFields() throws Exception {
        mockMvc.perform(post(REGISTRATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"connectorName\":\"a\",\"connectorType\":\"STANDARD\","
                                + "\"connectorVersion\":\"1.0.0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
        mockMvc.perform(post(REGISTRATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"connectorName\":\"conn-c\",\"connectorType\":\"STANDARD\","
                                + "\"connectorVersion\":\"1.0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
        mockMvc.perform(post(REGISTRATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"connectorName\":\"conn-d\",\"connectorType\":\"LIGHTWEIGHT\","
                                + "\"connectorVersion\":\"1.0.0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void applyReturns503WhenSubjectServiceUnavailable() throws Exception {
        stub().respondSubjectUnavailable();
        mockMvc.perform(post(REGISTRATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(registrationBody("conn-e")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("1010S0001"));
    }

    @Test
    void registrationDetailIsOwnerOrOperatorOnly() throws Exception {
        final String applicationNo = apply(OWNER, "conn-f");
        mockMvc.perform(get(REGISTRATIONS + "/" + applicationNo)
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
        mockMvc.perform(get(REGISTRATIONS + "/" + applicationNo)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationNo").value(applicationNo));
    }

    @Test
    void amendAllowedOnlyInPendingState() throws Exception {
        final String applicationNo = apply(OWNER, "conn-g");
        mockMvc.perform(put(REGISTRATIONS + "/" + applicationNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"connectorName\":\"conn-g2\",\"connectorType\":\"STANDARD\","
                                + "\"connectorVersion\":\"1.0.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectorName").value("conn-g2"))
                .andExpect(jsonPath("$.data.connectorVersion").value("1.0.1"));
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(put(REGISTRATIONS + "/" + applicationNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(registrationBody("conn-g3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
    }

    private long count(final String table) {
        final Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }
}
