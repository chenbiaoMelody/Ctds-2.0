package com.ctds.connector;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;

/** 读面与防枚举（hifi §9 T5：我的申请 / 待核验队列 / 详情 / 留痕 / 路径变量校验）。 */
class ConnectorQueryApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000041";
    private static final String OTHER = "S20261026000042";
    private static final String CONNECTORS = "/api/v1/connectors/";

    @Test
    void myRegistrationsPagedNewestFirst() throws Exception {
        apply(OWNER, "conn-a");
        apply(OWNER, "conn-b");
        mockMvc.perform(get(REGISTRATIONS + "/mine")
                        .param("pageNum", "1").param("pageSize", "1")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].connectorName").value("conn-b"));
    }

    @Test
    void reviewQueueOnlyPendingOldestFirst() throws Exception {
        apply(OWNER, "conn-a");
        apply(OWNER, "conn-b");
        registerConnector(OWNER, "conn-c");
        mockMvc.perform(get("/api/v1/connectors/review/queue")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].connectorName").value("conn-a"))
                .andExpect(jsonPath("$.data.list[1].connectorName").value("conn-b"));
    }

    @Test
    void detailHiddenFromOtherSubjects() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(get(CONNECTORS + connectorNo)
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"))
                .andExpect(jsonPath("$.message").value(ConnectorErrorCodes.NOT_FOUND_OR_DENIED_MESSAGE));
    }

    @Test
    void myConnectorsPagedWithDerivedOnline() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        registerConnector(OWNER, "conn-b");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/heartbeats")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(get(CONNECTORS + "mine")
                        .param("pageNum", "1").param("pageSize", "10")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.list[0].did").value(nullValue()))
                .andExpect(jsonPath("$.data.list[0].didStatus").value(nullValue()))
                .andExpect(jsonPath("$.data.list[0].online").value(false))
                .andExpect(jsonPath("$.data.list[1].online").value(true));
    }

    @Test
    void actionLogsContainApplyApprovalAndBindingRead() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/binding")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(get(CONNECTORS + connectorNo + "/action-logs")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.list[0].action").value("connector.apply"))
                .andExpect(jsonPath("$.data.list[1].action").value("connector.verify.approved"))
                .andExpect(jsonPath("$.data.list[2].action").value("connector.binding.read"));
    }

    @Test
    void invalidConnectorNoRejected() throws Exception {
        mockMvc.perform(get(CONNECTORS + "CN12")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
        mockMvc.perform(get(CONNECTORS + "CN1234567")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void paginationOutOfRangeRejected() throws Exception {
        mockMvc.perform(get(REGISTRATIONS + "/mine")
                        .param("pageSize", "101")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
    }
}
