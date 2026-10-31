package com.ctds.connector;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 在途作业占用台账（hifi §9 T5：Q2-A 内部面登记 / 释放 / 清单，幂等与状态门槛）。 */
class ConnectorOccupancyApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000091";

    private void occupyExpect(final String connectorNo, final String jobRef, final int expectedStatus,
            final String expectedCode) throws Exception {
        mockMvc.perform(post(INTERNAL + connectorNo + "/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content("{\"jobRef\":\"" + jobRef + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @Test
    void occupyRegistersInProgressJob() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(INTERNAL + connectorNo + "/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content("{\"jobRef\":\"job-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectorNo").value(connectorNo))
                .andExpect(jsonPath("$.data.jobRef").value("job-1"))
                .andExpect(jsonPath("$.data.status").value("OCCUPY"))
                .andExpect(jsonPath("$.data.occupiedAt").isNotEmpty());
    }

    @Test
    void duplicateOccupyIsIdempotent() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        occupy(connectorNo, "job-1");
        occupy(connectorNo, "job-1");
        mockMvc.perform(get(INTERNAL + connectorNo + "/occupancies")
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void releaseMakesJobNoLongerInProgress() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        occupy(connectorNo, "job-1");
        mockMvc.perform(delete(INTERNAL + connectorNo + "/occupancies/job-1")
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RELEASED"))
                .andExpect(jsonPath("$.data.releasedAt").isNotEmpty());
        mockMvc.perform(get(CONNECTORS + connectorNo + "/lifecycle")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inProgressJobs").value(0));
    }

    @Test
    void releaseTwiceIsIdempotent() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        occupy(connectorNo, "job-1");
        mockMvc.perform(delete(INTERNAL + connectorNo + "/occupancies/job-1")
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(delete(INTERNAL + connectorNo + "/occupancies/job-1")
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RELEASED"));
    }

    @Test
    void releaseUnknownJobRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(delete(INTERNAL + connectorNo + "/occupancies/job-404")
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void jobRefTooLongRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        final String tooLong = "j".repeat(65);
        mockMvc.perform(post(INTERNAL + connectorNo + "/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content("{\"jobRef\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void blankJobRefRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(INTERNAL + connectorNo + "/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content("{\"jobRef\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void occupyOnCanceledConnectorRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        cancel(OWNER, APPLICANT_ROLE, connectorNo);
        occupyExpect(connectorNo, "job-1", 400, "1010C0004");
    }

    @Test
    void occupyOnUnknownConnectorRejected() throws Exception {
        occupyExpect("CN999999", "job-1", 400, "1010C0003");
    }

    @Test
    void jobRefAtMaxLengthAccepted() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        occupy(connectorNo, "j".repeat(64));
        mockMvc.perform(get(INTERNAL + connectorNo + "/occupancies")
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].jobRef").value("j".repeat(64)));
    }

    @Test
    void businessRoleForbiddenOnInternalReadAndRelease() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        occupy(connectorNo, "job-1");
        mockMvc.perform(get(INTERNAL + connectorNo + "/occupancies")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
        mockMvc.perform(delete(INTERNAL + connectorNo + "/occupancies/job-1")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }

    @Test
    void businessRoleForbiddenOnInternalFace() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(INTERNAL + connectorNo + "/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"jobRef\":\"job-1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }

    @Test
    void adminForbiddenOnOccupyWrite() throws Exception {
        // E10 双向补齐（DB-89）：admin 仅持 connector.internal.read，不可自造占用凭证——
        // 占位端负向（读端 admin 直调见 ConnectorSourceReadCredentialTest）
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(post(INTERNAL + connectorNo + "/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE)
                        .content("{\"jobRef\":\"job-1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }

    @Test
    void unauthenticatedRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(get(INTERNAL + connectorNo + "/occupancies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"));
    }
}
