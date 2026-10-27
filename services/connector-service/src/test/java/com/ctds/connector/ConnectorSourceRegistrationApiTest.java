package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 源接入登记（WBS-3.6.5 hifi §9 T1；规格行为 11 验收 1 / 行为 12 验收 1）：
 * 四类源均受理、凭据只显掩码且落库密文、留痕齐备、重复登记与非法要素拒绝。
 */
class ConnectorSourceRegistrationApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";
    private static final String OTHER = "S20261026000052";

    @Test
    void registersAllFourSourceTypes() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-four");
        final List<String> types = List.of("GOV_EXCHANGE", "DATABASE", "OBJECT_STORAGE", "API_GATEWAY");
        int index = 0;
        for (final String type : types) {
            final String endpoint = "GOV_EXCHANGE".equals(type)
                    ? "mock://gov-exchange/ok" : "https://source.example.com/dataset-" + index;
            final JsonNode source = data(mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Ctds-Subject", OWNER)
                            .header("X-Ctds-Roles", APPLICANT_ROLE)
                            .content(sourceBody(type, endpoint, SOURCE_SECRET)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("0"))
                    .andReturn());
            assertThat(source.path("sourceNo").asText()).matches("^SR\\d{6}$");
            assertThat(source.path("sourceType").asText()).isEqualTo(type);
            assertThat(source.path("endpoint").asText()).isEqualTo(endpoint);
            assertThat(source.path("connectorNo").asText()).isEqualTo(connectorNo);
            assertThat(source.path("credentialRef").asText()).matches("^SC[0-9a-f]{32}$");
            index++;
        }
    }

    @Test
    void responseCarriesMaskOnlyAndNoPlaintext() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-mask");
        final MvcResult result = mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET)))
                .andExpect(status().isOk())
                .andReturn();
        final String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(SOURCE_SECRET);
        assertThat(data(result).path("credentialMasked").asText()).isEqualTo("****0001");
    }

    @Test
    void databaseKeepsCiphertextAndMaskOnly() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-cipher");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        final String ciphertext = jdbc.queryForObject(
                "SELECT credential_ciphertext FROM connector_source WHERE source_no = ?",
                String.class, sourceNo);
        final String masked = jdbc.queryForObject(
                "SELECT credential_masked FROM connector_source WHERE source_no = ?",
                String.class, sourceNo);
        assertThat(ciphertext).isNotNull();
        assertThat(ciphertext).doesNotContain(SOURCE_SECRET).doesNotContain("0001");
        assertThat(masked).isEqualTo("****0001");
    }

    @Test
    void registerWritesAuditTrailWithoutPlaintext() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-audit");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        final Integer success = jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_action_log WHERE action = 'source.register' "
                        + "AND result = 'SUCCESS' AND connector_no = ? AND detail LIKE ?",
                Integer.class, connectorNo, "sourceNo=" + sourceNo + "%");
        assertThat(success).isEqualTo(1);
        final Integer leaked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_action_log WHERE detail LIKE ?",
                Integer.class, "%" + SOURCE_SECRET + "%");
        assertThat(leaked).isZero();
    }

    @Test
    void duplicateRegistrationRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-dup");
        registerSource(OWNER, connectorNo, "GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", "another-secret-0002")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"))
                .andExpect(jsonPath("$.message").value("请求参数不合法"));
    }

    @Test
    void nonOwnerCannotRegisterSource() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-owner");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"))
                .andExpect(jsonPath("$.message").value("未找到该记录或无权访问"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM connector_source", Integer.class)).isZero();
    }

    @Test
    void unknownConnectorRejected() throws Exception {
        mockMvc.perform(post(CONNECTORS + "CN999999/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"))
                .andExpect(jsonPath("$.message").value("未找到该记录或无权访问"));
    }

    @Test
    void cancelledConnectorCannotRegisterSource() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-cancelled");
        cancel(OWNER, APPLICANT_ROLE, connectorNo);
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"))
                .andExpect(jsonPath("$.message").value("当前状态不允许该操作"));
    }

    @Test
    void mockSchemeAllowedOnlyForGovExchange() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-mock");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("DATABASE", "mock://gov-exchange/ok", SOURCE_SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void oversizedEndpointRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-long");
        final String endpoint = "https://source.example.com/" + "x".repeat(500);
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("DATABASE", endpoint, SOURCE_SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void emptyCredentialRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-blank");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void sourceRegistrationRequiresManagePermission() throws Exception {
        final String connectorNo = registerConnector(OWNER, "src-perm");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE)
                        .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }
}
