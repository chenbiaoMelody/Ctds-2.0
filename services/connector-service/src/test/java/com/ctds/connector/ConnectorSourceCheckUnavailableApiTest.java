package com.ctds.connector;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;

/**
 * 未开放源类型（WBS-3.6.5 hifi §9 T5；规格行为 11 规则 1 / 规则 3 诚实边界）：
 * 三类通用源登记受理（行为 11 验收 1），自检 / 读取明确拒绝（1010C0011，3.6.6 承接）。
 */
class ConnectorSourceCheckUnavailableApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";

    @Test
    void databaseSourceRegistersButCheckIsUnavailable() throws Exception {
        final String connectorNo = registerConnector(OWNER, "unavail-db");
        final String sourceNo = registerSource(OWNER, connectorNo, "DATABASE",
                "jdbc:mysql://db.example.com:3306/dataset", "db-secret-0003");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources/" + sourceNo + "/checks")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0011"))
                .andExpect(jsonPath("$.message").value("该源类型的适配承载尚未开放"));
    }

    @Test
    void databaseSourceReadIsUnavailable() throws Exception {
        final String connectorNo = registerConnector(OWNER, "unavail-read");
        final String sourceNo = registerSource(OWNER, connectorNo, "DATABASE",
                "jdbc:mysql://db.example.com:3306/dataset", "db-secret-0003");
        mockMvc.perform(post(INTERNAL + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody("CT0000000001", "统计分析")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0011"))
                .andExpect(jsonPath("$.message").value("该源类型的适配承载尚未开放"));
    }

    @Test
    void objectStorageAndApiGatewayAlsoUnavailable() throws Exception {
        final String connectorNo = registerConnector(OWNER, "unavail-more");
        final String oss = registerSource(OWNER, connectorNo, "OBJECT_STORAGE",
                "oss://bucket/dataset", "oss-secret-0004");
        final String api = registerSource(OWNER, connectorNo, "API_GATEWAY",
                "https://gateway.example.com/api", "api-secret-0005");
        for (final String sourceNo : new String[] {oss, api}) {
            mockMvc.perform(post(CONNECTORS + connectorNo + "/sources/" + sourceNo + "/checks")
                            .header("X-Ctds-Subject", OWNER)
                            .header("X-Ctds-Roles", APPLICANT_ROLE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("1010C0011"));
        }
    }
}
