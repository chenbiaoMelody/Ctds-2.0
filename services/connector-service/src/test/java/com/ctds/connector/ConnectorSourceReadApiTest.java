package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 源数据读取能力（WBS-3.6.5 hifi §9 T6；规格行为 14 验收 1/3、行为 11 规则 2、行为 13 规则 3）：
 * 合规读取返回摘要（不含本体）并留痕五要素；未登记 / 注销后拒绝；自检不等于授权。
 */
class ConnectorSourceReadApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";
    private static final String CONTRACT_NO = "CT0000000001";

    private static final String INTERNAL_READ = "/api/v1/connectors/internal/";

    private String internalRead(final String connectorNo, final String sourceNo, final String body)
            throws Exception {
        return mockMvc.perform(post(INTERNAL_READ + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void readSucceedsWithSummaryAndFiveElementTrail() throws Exception {
        final String connectorNo = registerConnector(OWNER, "read-ok");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(INTERNAL_READ + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody(CONTRACT_NO, "统计分析")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.sourceNo").value(sourceNo))
                .andExpect(jsonPath("$.data.connectorNo").value(connectorNo))
                .andExpect(jsonPath("$.data.contractNo").value(CONTRACT_NO))
                .andExpect(jsonPath("$.data.summary").isNotEmpty())
                .andExpect(jsonPath("$.data.readAt").isNotEmpty());
        final String detail = jdbc.queryForObject(
                "SELECT detail FROM connector_action_log WHERE action = 'source.read' "
                        + "AND connector_no = ? ORDER BY id DESC LIMIT 1",
                String.class, connectorNo);
        assertThat(detail).contains(sourceNo).contains(CONTRACT_NO).contains("success=true");
        final String operator = jdbc.queryForObject(
                "SELECT operator_no FROM connector_action_log WHERE action = 'source.read' "
                        + "AND connector_no = ? ORDER BY id DESC LIMIT 1",
                String.class, connectorNo);
        assertThat(operator).isEqualTo("delivery-service");
        assertThat(detail).doesNotContain(SOURCE_SECRET);
    }

    @Test
    void summaryCarriesNoDataBody() throws Exception {
        final String connectorNo = registerConnector(OWNER, "read-summary");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        final String body = internalRead(connectorNo, sourceNo, readBody(CONTRACT_NO, "统计分析"));
        assertThat(body).doesNotContain(SOURCE_SECRET);
        assertThat(body).doesNotContain("payload").doesNotContain("row-data");
    }

    @Test
    void readWorksWithoutPriorCheck() throws Exception {
        final String connectorNo = registerConnector(OWNER, "read-nocheck");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(INTERNAL_READ + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody(CONTRACT_NO, "统计分析")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
        final Integer checks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_action_log WHERE action = 'source.check'",
                Integer.class);
        assertThat(checks).isZero();
    }

    @Test
    void unregisteredSourceRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "read-unregistered");
        mockMvc.perform(post(INTERNAL_READ + connectorNo + "/sources/SR999999/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody(CONTRACT_NO, "统计分析")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"))
                .andExpect(jsonPath("$.message").value("未找到该记录或无权访问"));
    }

    @Test
    void cancelledConnectorCannotReadButArchiveRemains() throws Exception {
        final String connectorNo = registerConnector(OWNER, "read-cancelled");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        cancel(OWNER, APPLICANT_ROLE, connectorNo);
        mockMvc.perform(post(INTERNAL_READ + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody(CONTRACT_NO, "统计分析")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"))
                .andExpect(jsonPath("$.message").value("当前状态不允许该操作"));
        mockMvc.perform(get(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceNo").value(sourceNo));
    }

    @Test
    void missingContractNoRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "read-nocontract");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(INTERNAL_READ + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content("{\"purpose\":\"统计分析\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void malformedContractNoRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "read-badcontract");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(INTERNAL_READ + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody("CT 0001/../x", "统计分析")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void internalReadRequiresServiceIdentity() throws Exception {
        final String connectorNo = registerConnector(OWNER, "read-perm");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(INTERNAL_READ + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(readBody(CONTRACT_NO, "统计分析")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }
}
