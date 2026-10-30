package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;

/**
 * 连通性自检（WBS-3.6.5 hifi §9 T4；规格行为 13 验收 1~4）：
 * 通过 = 200 业务结论；三类失败原因逐一可读；失败留痕；自检不产生源端数据变更。
 */
class ConnectorSourceCheckApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";

    /** 登记连接器 + 源，返回 [connectorNo, sourceNo]。 */
    private String[] checkedSource(final String name, final String endpoint) throws Exception {
        final String connectorNo = registerConnector(OWNER, name);
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE", endpoint, SOURCE_SECRET);
        return new String[] {connectorNo, sourceNo};
    }

    private String checkPath(final String[] ids) {
        return CONNECTORS + ids[0] + "/sources/" + ids[1] + "/checks";
    }

    private String detailPath(final String[] ids) {
        return CONNECTORS + ids[0] + "/sources/" + ids[1];
    }

    @Test
    void healthySourcePassesCheck() throws Exception {
        final String[] ids = checkedSource("chk-ok", "mock://gov-exchange/ok");
        mockMvc.perform(post(checkPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.failureType").doesNotExist())
                .andExpect(jsonPath("$.data.message").isNotEmpty());
    }

    @Test
    void invalidCredentialIsReadableFailureType() throws Exception {
        final String[] ids = checkedSource("chk-cred", "mock://gov-exchange/bad-credential");
        mockMvc.perform(post(checkPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.failureType").value("INVALID_CREDENTIAL"))
                .andExpect(jsonPath("$.data.message").value("凭据无效"));
    }

    @Test
    void networkUnreachableIsReadableFailureType() throws Exception {
        final String[] ids = checkedSource("chk-net", "mock://gov-exchange/unreachable");
        mockMvc.perform(post(checkPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.failureType").value("NETWORK_UNREACHABLE"))
                .andExpect(jsonPath("$.data.message").value("网络不可达"));
    }

    @Test
    void insufficientPermissionIsReadableFailureType() throws Exception {
        final String[] ids = checkedSource("chk-perm", "mock://gov-exchange/forbidden");
        mockMvc.perform(post(checkPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.failureType").value("INSUFFICIENT_PERMISSION"))
                .andExpect(jsonPath("$.data.message").value("权限不足"));
    }

    @Test
    void checkWritesSuccessTrail() throws Exception {
        final String[] ids = checkedSource("chk-trail", "mock://gov-exchange/ok");
        mockMvc.perform(post(checkPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        final Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_action_log WHERE action = 'source.check' "
                        + "AND result = 'SUCCESS' AND connector_no = ? AND detail LIKE ?",
                Integer.class, ids[0], "sourceNo=" + ids[1] + "%");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void checkWritesFailureTrailWithReason() throws Exception {
        final String[] ids = checkedSource("chk-fail-trail", "mock://gov-exchange/bad-credential");
        mockMvc.perform(post(checkPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(jsonPath("$.data.passed").value(false));
        final String reason = jdbc.queryForObject(
                "SELECT reason_code FROM connector_action_log WHERE action = 'source.check' "
                        + "AND result = 'FAILURE' AND connector_no = ? ORDER BY id DESC LIMIT 1",
                String.class, ids[0]);
        assertThat(reason).isEqualTo("INVALID_CREDENTIAL");
    }

    @Test
    void checkDoesNotChangeSourceData() throws Exception {
        final String[] ids = checkedSource("chk-readonly", "mock://gov-exchange/ok");
        mockMvc.perform(post(checkPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(jsonPath("$.data.passed").value(true));
        assertThat(govAdapter().mockExchangeCount()).isPositive();
        assertThat(govAdapter().mockDataVersion()).isZero();
    }

    @Test
    void detailShowsLatestCheckConclusion() throws Exception {
        final String[] ids = checkedSource("chk-detail", "mock://gov-exchange/ok");
        mockMvc.perform(post(checkPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(get(detailPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastCheck.passed").value(true))
                .andExpect(jsonPath("$.data.lastCheck.checkedAt").isNotEmpty());
    }

    @Test
    void detailLastCheckMatchesLatestSuccessTrail() throws Exception {
        final String[] ids = checkedSource("chk-db90-ok", "mock://gov-exchange/ok");
        mockMvc.perform(post(checkPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        final String trailDetail = jdbc.queryForObject(
                "SELECT detail FROM connector_action_log WHERE action = 'source.check' "
                        + "AND connector_no = ? ORDER BY id DESC LIMIT 1",
                String.class, ids[0]);
        mockMvc.perform(get(detailPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastCheck.passed").value(true))
                .andExpect(jsonPath("$.data.lastCheck.message")
                        .value(trailDetail.replaceAll(".*message=", "")));
    }

    @Test
    void detailLastCheckMatchesLatestFailureTrail() throws Exception {
        final String[] ids = checkedSource("chk-db90-fail", "mock://gov-exchange/bad-credential");
        mockMvc.perform(post(checkPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(get(detailPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastCheck.passed").value(false))
                .andExpect(jsonPath("$.data.lastCheck.failureType").value("INVALID_CREDENTIAL"))
                .andExpect(jsonPath("$.data.lastCheck.message").value("凭据无效"));
    }

    @Test
    void detailWithoutCheckHasNoConclusion() throws Exception {
        final String[] ids = checkedSource("chk-none", "mock://gov-exchange/ok");
        mockMvc.perform(get(detailPath(ids))
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastCheck").doesNotExist());
    }
}
