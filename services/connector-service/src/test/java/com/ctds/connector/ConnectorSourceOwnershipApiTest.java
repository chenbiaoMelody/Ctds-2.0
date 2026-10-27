package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;

/**
 * 源归属与防枚举（WBS-3.6.5 hifi §9 T2；规格行为 12 验收 3）：
 * 非本归属 / 不存在 / 跨连接器一律同码同文 1010C0003；运营方可读清单与详情。
 */
class ConnectorSourceOwnershipApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";
    private static final String OTHER = "S20261026000052";

    @Test
    void nonOwnerCannotReadList() throws Exception {
        final String connectorNo = registerConnector(OWNER, "own-list");
        registerSource(OWNER, connectorNo, "GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(get(CONNECTORS + connectorNo + "/sources")
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"))
                .andExpect(jsonPath("$.message").value("未找到该记录或无权访问"));
    }

    @Test
    void nonOwnerCannotReadDetail() throws Exception {
        final String connectorNo = registerConnector(OWNER, "own-detail");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(get(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"))
                .andExpect(jsonPath("$.message").value("未找到该记录或无权访问"));
    }

    @Test
    void crossConnectorSourceNoLooksMissing() throws Exception {
        final String ownerConnector = registerConnector(OWNER, "own-a");
        final String otherConnector = registerConnector(OTHER, "own-b");
        final String sourceNo = registerSource(OWNER, ownerConnector, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(get(CONNECTORS + otherConnector + "/sources/" + sourceNo)
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"))
                .andExpect(jsonPath("$.message").value("未找到该记录或无权访问"));
    }

    @Test
    void unknownSourceNoUsesSameCodeAndMessage() throws Exception {
        final String connectorNo = registerConnector(OWNER, "own-unknown");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/sources/SR999999")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"))
                .andExpect(jsonPath("$.message").value("未找到该记录或无权访问"));
    }

    @Test
    void nonOwnerCannotCheck() throws Exception {
        final String connectorNo = registerConnector(OWNER, "own-check");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources/" + sourceNo + "/checks")
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
        assertThat(govAdapter().mockExchangeCount()).isZero();
    }

    @Test
    void operatorCanReadListAndDetail() throws Exception {
        final String connectorNo = registerConnector(OWNER, "own-operator");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(get(CONNECTORS + connectorNo + "/sources")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].sourceNo").value(sourceNo));
        mockMvc.perform(get(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceNo").value(sourceNo));
    }

    @Test
    void listIsPagedAndExcludesCheckConclusion() throws Exception {
        final String connectorNo = registerConnector(OWNER, "own-page");
        registerSource(OWNER, connectorNo, "GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET);
        registerSource(OWNER, connectorNo, "DATABASE", "https://source.example.com/db-1", "db-secret-0003");
        mockMvc.perform(get(CONNECTORS + connectorNo + "/sources")
                        .param("pageNum", "1")
                        .param("pageSize", "1")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].lastCheck").doesNotExist());
    }
}
