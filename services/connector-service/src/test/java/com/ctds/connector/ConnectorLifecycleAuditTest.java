package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 生命周期留痕（hifi §9 T6：六类新动作 + 拒绝留痕 + 无秘密材料）。 */
class ConnectorLifecycleAuditTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000101";
    private static final String OTHER = "S20261026000102";

    @Test
    void sixLifecycleActionsAreTraced() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-a");
        upgrade(OWNER, APPLICANT_ROLE, connectorNo, "1.1.0", "COMPATIBLE");
        final String upgradeNo = upgrade(OWNER, APPLICANT_ROLE, connectorNo, "2.0.0", "INCOMPATIBLE")
                .path("upgradeNo").asText();
        occupy(connectorNo, "job-1");
        mockMvc.perform(delete(INTERNAL + connectorNo + "/occupancies/job-1")
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades/" + upgradeNo + "/confirmation")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk());
        cancel(OWNER, APPLICANT_ROLE, connectorNo);

        final List<String> actions = jdbc.queryForList("SELECT action FROM connector_action_log "
                + "WHERE connector_no = ? ORDER BY id", String.class, connectorNo);
        assertThat(actions).contains("connector.upgrade.applied", "connector.upgrade.requested",
                "connector.upgrade.confirmed", "connector.occupancy.register",
                "connector.occupancy.release", "connector.cancelled");
    }

    @Test
    void deniedCancellationIsTracedWithReasonCode() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-b");
        occupy(connectorNo, "job-1");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/cancellation")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadRequest());
        final String reasonCode = jdbc.queryForObject("SELECT reason_code FROM connector_action_log "
                + "WHERE action = 'connector.cancelled' AND result = 'DENIED'", String.class);
        assertThat(reasonCode).isEqualTo("1010C0008");
    }

    @Test
    void deniedUpgradeIsTraced() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-c");
        mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OTHER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content("{\"toVersion\":\"1.1.0\",\"compatibility\":\"COMPATIBLE\"}"))
                .andExpect(status().isBadRequest());
        final Long denied = jdbc.queryForObject("SELECT COUNT(*) FROM connector_action_log "
                + "WHERE action = 'connector.denied' AND connector_no = ?", Long.class, connectorNo);
        assertThat(denied).isEqualTo(1L);
    }

    @Test
    void lifecycleTraceDetailContainsNoCredentialWords() throws Exception {
        final String connectorNo = registerConnector(OWNER, "conn-d");
        upgrade(OWNER, APPLICANT_ROLE, connectorNo, "1.1.0", "COMPATIBLE");
        occupy(connectorNo, "job-ref-1");
        mockMvc.perform(delete(INTERNAL + connectorNo + "/occupancies/job-ref-1")
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk());
        cancel(OWNER, APPLICANT_ROLE, connectorNo);
        final List<String> details = jdbc.queryForList(
                "SELECT COALESCE(detail, '') FROM connector_action_log", String.class);
        assertThat(details).isNotEmpty();
        assertThat(details).allSatisfy(detail -> {
            final String lower = detail.toLowerCase();
            assertThat(lower).doesNotContain("password").doesNotContain("passwd")
                    .doesNotContain("secret").doesNotContain("token").doesNotContain("apikey")
                    .doesNotContain("privatekey").doesNotContain("credential")
                    .doesNotContain("密码").doesNotContain("口令").doesNotContain("密钥");
        });
    }
}
