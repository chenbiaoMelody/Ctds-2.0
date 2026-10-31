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

/**
 * 源接入边界与负向用例（WBS-3.6.5 评审循环 1 修复批，补 hifi §5 错误面）：
 * 地址安全校验（URL 内嵌凭据 / 查询串凭据键 / 非白名单 scheme）、非法枚举、参数越界、状态门槛、
 * 鉴权拒绝（401 / 403）、读取失败的业务结论、拒绝类留痕、留痕地址脱敏。
 */
class ConnectorSourceBoundaryApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";
    private static final String CONTRACT_NO = "CT0000000001";
    private static final String SOURCES = "/sources";

    @Test
    void rejectsEndpointWithEmbeddedCredential() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-userinfo");
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("GOV_EXCHANGE", "https://svc:Pa55w0rd@gov.example.com/api",
                                SOURCE_SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM connector_source", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_action_log WHERE detail LIKE '%Pa55w0rd%'", Integer.class))
                .isZero();
    }

    @Test
    void rejectsNonWhitelistedScheme() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-scheme");
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("DATABASE", "file:///etc/passwd", SOURCE_SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void rejectsMockAddressForNonGovType() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-mock-type");
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("API_GATEWAY", "mock://gov-exchange/ok", SOURCE_SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void rejectsInvalidSourceTypeWithPlatformParamCode() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-enum");
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"sourceType\":\"NOT_A_TYPE\",\"endpoint\":\"https://a.example.com/x\","
                                + "\"credential\":\"" + SOURCE_SECRET + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
    }

    @Test
    void rejectsOversizeCredential() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-cred-size");
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", "c".repeat(1025))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM connector_source", Integer.class)).isZero();
    }

    @Test
    void rejectsPaginationOutOfRangeWithPlatformParamCode() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-page");
        mockMvc.perform(get(CONNECTORS + connectorNo + SOURCES)
                        .param("pageNum", "1")
                        .param("pageSize", "1000")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
    }

    @Test
    void checkRejectedAfterConnectorCancelled() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-cancel");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        cancel(OWNER, APPLICANT_ROLE, connectorNo);
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES + "/" + sourceNo + "/checks")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
    }

    @Test
    void rejectsOversizeContractNoAndPurpose() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-read-param");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(INTERNAL + connectorNo + SOURCES + "/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody("C".repeat(65), "统计分析")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
        mockMvc.perform(post(INTERNAL + connectorNo + SOURCES + "/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody(CONTRACT_NO, "统".repeat(129))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-anon");
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsCallerWithoutManagePermission() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-403");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES + "/" + sourceNo + "/checks")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
        mockMvc.perform(get(CONNECTORS + connectorNo + SOURCES + "/" + sourceNo)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(CONNECTORS + connectorNo + SOURCES + "/" + sourceNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE)
                        .content("{\"endpoint\":\"mock://gov-exchange/ok\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void readFailureIsBusinessConclusionWithFailureTrail() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-read-fail");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/bad-credential", SOURCE_SECRET);
        occupy(connectorNo, "job-1");
        mockMvc.perform(post(INTERNAL + connectorNo + SOURCES + "/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBodyWithJobRef(CONTRACT_NO, "统计分析", "job-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.summary").value(org.hamcrest.Matchers.containsString("凭据无效")));
        final String reason = jdbc.queryForObject(
                "SELECT reason_code FROM connector_action_log WHERE action = 'source.read' "
                        + "AND result = 'FAILURE' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(reason).isEqualTo("READ_FAILED");
    }

    @Test
    void rejectsCredentialLikeQueryKeyEndpoint() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-query-cred");
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("GOV_EXCHANGE",
                                "https://gov.example.com/api?password=Pa55w0rd", SOURCE_SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM connector_source", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_action_log WHERE detail LIKE '%Pa55w0rd%'", Integer.class))
                .isZero();
    }

    @Test
    void auditTrailRedactsEndpointQueryString() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-redact");
        final String endpoint = "http://127.0.0.1:1/gov?stage=demo123";
        registerSource(OWNER, connectorNo, "GOV_EXCHANGE", endpoint, SOURCE_SECRET);
        final String detail = jdbc.queryForObject(
                "SELECT detail FROM connector_action_log WHERE action = 'source.register' "
                        + "ORDER BY id DESC LIMIT 1", String.class);
        assertThat(detail).contains("endpoint=http://127.0.0.1:1/gov");
        assertThat(detail).doesNotContain("stage").doesNotContain("demo123");
    }

    @Test
    void rejectsMalformedPathVariables() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-path");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(get(CONNECTORS + "CN12/sources")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES + "/SR1/checks")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
        assertThat(sourceNo).matches("^SR\\d{6}$");
    }

    @Test
    void rejectsListForCallerWithoutReadPermission() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-list-403");
        mockMvc.perform(get(CONNECTORS + connectorNo + SOURCES)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }

    @Test
    void amendRejectsEndpointAlreadyUsedByAnotherSource() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-amend-dup");
        registerSource(OWNER, connectorNo, "GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET);
        final String second = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/forbidden", SOURCE_SECRET);
        mockMvc.perform(put(CONNECTORS + connectorNo + SOURCES + "/" + second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"endpoint\":\"mock://gov-exchange/ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
    }

    @Test
    void duplicateRegistrationWritesDeniedTrail() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-denied");
        registerSource(OWNER, connectorNo, "GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody("GOV_EXCHANGE", "mock://gov-exchange/ok", SOURCE_SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_action_log WHERE action = 'source.denied'", Integer.class))
                .isPositive();
    }

    @Test
    void restrictedHostCheckWritesFailureTrail() throws Exception {
        final String connectorNo = registerConnector(OWNER, "bnd-restricted");
        final String sourceNo = registerSource(OWNER, connectorNo, "DATABASE",
                "jdbc:mysql://no-such-host.invalid:3306/dataset", "db-user:db-secret-0003");
        mockMvc.perform(post(CONNECTORS + connectorNo + SOURCES + "/" + sourceNo + "/checks")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.failureType").value("NETWORK_UNREACHABLE"));
        final String reason = jdbc.queryForObject(
                "SELECT reason_code FROM connector_action_log WHERE action = 'source.check' "
                        + "AND result = 'FAILURE' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(reason).isEqualTo("NETWORK_UNREACHABLE");
    }
}
