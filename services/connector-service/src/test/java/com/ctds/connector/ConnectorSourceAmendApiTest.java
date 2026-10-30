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
 * 源配置变更（WBS-3.6.5 hifi §9 T3；规格行为 12 验收 2）：
 * 留痕记录旧 / 新源地址、凭据仍以掩码呈现、凭据不变时密文与掩码不变、变更后可重新自检。
 */
class ConnectorSourceAmendApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";
    private static final String OTHER = "S20261026000052";

    private static String amendBody(final String endpoint, final String credential) {
        return credential == null
                ? "{\"endpoint\":\"" + endpoint + "\"}"
                : "{\"endpoint\":\"" + endpoint + "\",\"credential\":\"" + credential + "\"}";
    }

    @Test
    void amendRecordsOldAndNewEndpoint() throws Exception {
        final String connectorNo = registerConnector(OWNER, "amd-endpoint");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(put(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(amendBody("mock://gov-exchange/forbidden", "rotated-secret-0009")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceNo").value(sourceNo))
                .andExpect(jsonPath("$.data.endpoint").value("mock://gov-exchange/forbidden"))
                .andExpect(jsonPath("$.data.credentialMasked").value("****0009"));
        final String detail = jdbc.queryForObject(
                "SELECT detail FROM connector_action_log WHERE action = 'source.amend' "
                        + "AND connector_no = ? ORDER BY id DESC LIMIT 1",
                String.class, connectorNo);
        assertThat(detail).contains("mock://gov-exchange/ok")
                .contains("mock://gov-exchange/forbidden");
        assertThat(detail).doesNotContain("rotated-secret-0009").doesNotContain(SOURCE_SECRET);
    }

    @Test
    void amendWithoutCredentialKeepsCipherAndMask() throws Exception {
        final String connectorNo = registerConnector(OWNER, "amd-keep");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        final String before = jdbc.queryForObject(
                "SELECT credential_ciphertext FROM connector_source WHERE source_no = ?",
                String.class, sourceNo);
        mockMvc.perform(put(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(amendBody("mock://gov-exchange/ok", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.credentialMasked").value("****0001"));
        final String after = jdbc.queryForObject(
                "SELECT credential_ciphertext FROM connector_source WHERE source_no = ?",
                String.class, sourceNo);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void amendedSourceCanBeChecked() throws Exception {
        final String connectorNo = registerConnector(OWNER, "amd-check");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/bad-credential", SOURCE_SECRET);
        mockMvc.perform(put(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(amendBody("mock://gov-exchange/ok", "rotated-secret-0009")))
                .andExpect(status().isOk());
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources/" + sourceNo + "/checks")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(true));
    }

    @Test
    void amendGenericSourceRecordsMaskOnly() throws Exception {
        final String connectorNo = registerConnector(OWNER, "amd-generic");
        final String sourceNo = registerSource(OWNER, connectorNo, "API_GATEWAY",
                "https://gateway.example.com/api", "Bearer gateway-token-0005");

        mockMvc.perform(put(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(amendBody("https://gateway.example.com/api-v2",
                                "Bearer rotated-token-0009")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.credentialMasked").value("****0009"));

        final String detail = jdbc.queryForObject(
                "SELECT detail FROM connector_action_log WHERE action = 'source.amend' "
                        + "AND connector_no = ? ORDER BY id DESC LIMIT 1", String.class, connectorNo);
        assertThat(detail).contains("https://gateway.example.com/api")
                .contains("api-v2")
                .contains("credential=changed;masked=****0009");
        assertThat(detail).doesNotContain("rotated-token-0009")
                .doesNotContain("gateway-token-0005");
    }

    @Test
    void amendedDatabaseSourceChecksPass() throws Exception {
        final String connectorNo = registerConnector(OWNER, "amd-db");
        final String sourceNo = registerSource(OWNER, connectorNo, "DATABASE",
                databaseUrl(), "wrong-user:wrong-password");
        mockMvc.perform(put(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(amendBody(databaseUrl(), databaseCredential())))
                .andExpect(status().isOk());
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources/" + sourceNo + "/checks")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(true));
    }

    @Test
    void crossOwnershipAmendHidden() throws Exception {
        final String connectorNo = registerConnector(OWNER, "amd-cross");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(put(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(amendBody("mock://gov-exchange/ok", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void amendUnknownSourceHidden() throws Exception {
        final String connectorNo = registerConnector(OWNER, "amd-unknown");
        mockMvc.perform(put(CONNECTORS + connectorNo + "/sources/SR999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(amendBody("mock://gov-exchange/ok", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0003"));
    }

    @Test
    void amendOnCancelledConnectorRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "amd-cancelled");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        cancel(OWNER, APPLICANT_ROLE, connectorNo);
        mockMvc.perform(put(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(amendBody("mock://gov-exchange/ok", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
        mockMvc.perform(get(CONNECTORS + connectorNo + "/sources/" + sourceNo)
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
    }
}
