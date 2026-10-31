package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 内部读取作业凭证（WBS-3.6.8 W6 / DB-89，hifi §1-C）：读取请求必填 {@code jobRef} 且须存在
 * OCCUPY 占用记录（占用登记仅 {@code connector-internal} 服务身份可写，admin 不可自造 → 凭证不可伪造）。
 *
 * <ul>
 *   <li><b>T5-①</b>：缺 jobRef → 参数无效；</li>
 *   <li><b>T5-②</b>：jobRef 无占用 / 已释放 → 拒绝（作业未登记或已释放），适配器零调用；</li>
 *   <li><b>T5-③</b>：admin 持 {@code connector.internal.read} 直调（无占用）→ 拒绝；</li>
 *   <li><b>T5-④</b>：占用后读取成功（凭证正腿）。</li>
 * </ul>
 */
class ConnectorSourceReadCredentialTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000091";
    private static final String CONTRACT_NO = "CT0000000091";

    private static String bodyWithJobRef(final String jobRef) {
        return "{\"contractNo\":\"" + CONTRACT_NO + "\",\"purpose\":\"统计分析\",\"jobRef\":\""
                + jobRef + "\"}";
    }

    private String read(final String role, final String connectorNo, final String sourceNo,
            final String body) throws Exception {
        return mockMvc.perform(post(INTERNAL + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", role)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void readWithoutJobRefRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "cred-missing");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(INTERNAL + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody(CONTRACT_NO, "统计分析")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0001"));
        assertThat(govAdapter().mockExchangeCount()).as("凭证校验拒绝时适配器零调用").isZero();
    }

    @Test
    void readWithUnoccupiedJobRefRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "cred-unoccupied");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(INTERNAL + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(bodyWithJobRef("DL000099")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"))
                .andExpect(jsonPath("$.message").value("作业未登记或已释放"));
        assertThat(govAdapter().mockExchangeCount()).as("无占用凭证适配器零调用").isZero();
    }

    @Test
    void readWithReleasedJobRefRejected() throws Exception {
        final String connectorNo = registerConnector(OWNER, "cred-released");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        occupy(connectorNo, "DL000098");
        mockMvc.perform(delete(INTERNAL + connectorNo + "/occupancies/DL000098")
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE))
                .andExpect(status().isOk());
        mockMvc.perform(post(INTERNAL + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(bodyWithJobRef("DL000098")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"))
                .andExpect(jsonPath("$.message").value("作业未登记或已释放"));
        assertThat(govAdapter().mockExchangeCount()).as("已释放凭证适配器零调用").isZero();
    }

    @Test
    void adminDirectReadWithoutOccupancyRejected() throws Exception {
        // 凭证不可伪造：admin 仅持 connector.internal.read，占用记录须 connector.internal.write
        // （仅 connector-internal 服务角色）——admin 无法自造凭证，直调读取被拒
        final String connectorNo = registerConnector(OWNER, "cred-admin");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        mockMvc.perform(post(INTERNAL + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE)
                        .content(bodyWithJobRef("DL000097")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0004"));
        assertThat(govAdapter().mockExchangeCount()).as("admin 直调适配器零调用").isZero();
    }

    @Test
    void readWithOccupiedJobRefSucceeds() throws Exception {
        final String connectorNo = registerConnector(OWNER, "cred-ok");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                "mock://gov-exchange/ok", SOURCE_SECRET);
        occupy(connectorNo, "DL000096");
        mockMvc.perform(post(INTERNAL + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(bodyWithJobRef("DL000096")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.success").value(true));
        assertThat(govAdapter().mockExchangeCount()).as("凭证正腿适配器恰好调用一次").isEqualTo(1);
    }
}
