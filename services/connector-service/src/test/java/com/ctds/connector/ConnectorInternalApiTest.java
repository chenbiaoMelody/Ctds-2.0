package com.ctds.connector;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;

/** 内部消费面（hifi §9 T6：最小暴露 + 权限点 connector.internal.read）。 */
class ConnectorInternalApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";
    private static final String INTERNAL = "/api/v1/connectors/internal/";

    @Test
    void internalReadableByServiceIdentity() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(get(INTERNAL + connectorNo)
                        .header("X-Ctds-Subject", "connector-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectorNo").value(connectorNo))
                .andExpect(jsonPath("$.data.subjectNo").value(OWNER))
                .andExpect(jsonPath("$.data.did").value("did:ctds:S20261026000001.1"))
                .andExpect(jsonPath("$.data.didStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.verified").value(true));
    }

    @Test
    void internalReflectsRealtimeDidStatusInsteadOfSnapshot() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-realtime");
        stub().respondDid("REVOKED", "did:ctds:S20261026000051.1");
        mockMvc.perform(get(INTERNAL + connectorNo)
                        .header("X-Ctds-Subject", "connector-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.didStatus").value("REVOKED"))
                .andExpect(jsonPath("$.data.verified").value(false));
    }

    @Test
    void internalReadableByAdmin() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-b");
        mockMvc.perform(get(INTERNAL + connectorNo)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk());
    }

    @Test
    void internalDeniedForBusinessRole() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-c");
        mockMvc.perform(get(INTERNAL + connectorNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }

    @Test
    void internalRequiresAuthentication() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-d");
        mockMvc.perform(get(INTERNAL + connectorNo))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"));
    }

    @Test
    void internalHidesUnknownConnector() throws Exception {
        mockMvc.perform(get(INTERNAL + "CN999999")
                        .header("X-Ctds-Subject", "connector-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    /** W9 / DB-68 路径统一：全仓内部面惯例 {@code /api/v1/{域}/internal/{资源}}，消除重复域段。 */
    @Test
    void internalReadableOnUnifiedPath() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-unified");
        mockMvc.perform(get(INTERNAL + connectorNo)
                        .header("X-Ctds-Subject", "connector-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectorNo").value(connectorNo))
                .andExpect(jsonPath("$.data.subjectNo").value(OWNER));
    }
}
