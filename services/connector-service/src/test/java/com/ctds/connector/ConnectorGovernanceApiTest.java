package com.ctds.connector;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.support.ConnectorTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 运营方全平台连接器列表（WBS-3.6.7 Q3-A；hifi §8.2 T16）：
 * 加性只读端点 GET /api/v1/connectors/governance/connectors（connector.review）；
 * 状态过滤 + 分页 + online 读时派生；非法 status 400 可读（不得落 500）；零新表 / 零新码 / 零迁移。
 */
class ConnectorGovernanceApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000041";
    private static final String OTHER = "S20261026000042";
    private static final String GOVERNANCE = "/api/v1/connectors/governance/connectors";

    @Test
    void adminSeesAllThreeStatesNewestFirst() throws Exception {
        apply(OWNER, "conn-a");
        registerConnector(OWNER, "conn-b");
        final String cancelledApplicationNo = apply(OTHER, "conn-c");
        mockMvc.perform(delete(REGISTRATIONS + "/" + cancelledApplicationNo)
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(get(GOVERNANCE)
                        .param("pageNum", "1").param("pageSize", "10")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.list.length()").value(3))
                .andExpect(jsonPath("$.data.list[0].connectorName").value("conn-c"))
                .andExpect(jsonPath("$.data.list[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.list[1].connectorName").value("conn-b"))
                .andExpect(jsonPath("$.data.list[1].status").value("REGISTERED"))
                .andExpect(jsonPath("$.data.list[2].connectorName").value("conn-a"))
                .andExpect(jsonPath("$.data.list[2].status").value("PENDING_REVIEW"));
    }

    @Test
    void statusFilterNarrowsResult() throws Exception {
        apply(OWNER, "conn-a");
        registerConnector(OWNER, "conn-b");
        mockMvc.perform(get(GOVERNANCE)
                        .param("status", "REGISTERED")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].connectorName").value("conn-b"))
                .andExpect(jsonPath("$.data.list[0].connectorNo").isNotEmpty());
        mockMvc.perform(get(GOVERNANCE)
                        .param("status", "PENDING_REVIEW")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].connectorName").value("conn-a"))
                .andExpect(jsonPath("$.data.list[0].connectorNo").value(nullValue()));
    }

    @Test
    void applicantForbiddenWithPlatformCode() throws Exception {
        mockMvc.perform(get(GOVERNANCE)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"))
                .andExpect(jsonPath("$.message").value("无权限执行该操作"));
    }

    @Test
    void onlineDerivedFromServerHeartbeatThreshold() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        registerConnector(OWNER, "conn-b");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/heartbeats")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(get(GOVERNANCE)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[1].connectorName").value("conn-a"))
                .andExpect(jsonPath("$.data.list[1].online").value(true))
                .andExpect(jsonPath("$.data.list[0].online").value(false));
        // 心跳陈旧（超过 5m 阈值）→ 读时派生为离线
        jdbc.update("UPDATE connector_registration SET last_heartbeat_at = ? WHERE connector_no = ?",
                LocalDateTime.now().minusMinutes(10), connectorNo);
        mockMvc.perform(get(GOVERNANCE)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[1].online").value(false));
    }

    @Test
    void invalidStatusRejectedAsReadableBadRequest() throws Exception {
        mockMvc.perform(get(GOVERNANCE)
                        .param("status", "BOGUS")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"))
                .andExpect(jsonPath("$.message").value(ConnectorErrorCodes.PARAM_INVALID_MESSAGE));
    }

    @Test
    void paginationOutOfRangeRejected() throws Exception {
        mockMvc.perform(get(GOVERNANCE)
                        .param("pageSize", "101")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
    }

    @Test
    void summaryCarriesNoDidFields() throws Exception {
        registerConnector(OWNER, "conn-a");
        mockMvc.perform(get(GOVERNANCE)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].did").value(nullValue()))
                .andExpect(jsonPath("$.data.list[0].didStatus").value(nullValue()))
                .andExpect(jsonPath("$.data.list[0].verified").value(false));
    }
}
