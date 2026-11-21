package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.SharedMySqlContainer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WBS-3.10.4 T1~T6/T8（hifi §1/§7）：AI 配置面真库行为测试（MockMvc + Testcontainers MySQL +
 * jdbc 源真跑 Flyway V1~V6，沿 RbacGovernanceBehaviorIntegrationTest 范式）：
 * - T1 缺省视图（seed 态 = C-11.1 缺省）+ T8 读响应零密钥字段（api_key_ref 不出站，红线）；
 * - T2 保存后回读；T3 保存成功逐字段留痕（仅变化字段）+ 全同保存零新留痕；
 * - T4 校验拒绝边界值全覆盖（清单外编号 / 缺行 / 重复行 / 默认供应商不在清单 / 负预算 /
 *   非整数数字与文本 / 非数字文本 / 预算缺参 / 开关缺失或 null / 非法或超长 URL / 超长模型名）零变化
 *   （评审修复批 P2-A/P2-B：hifi §8 边界值表后端权威面逐行承载）；
 * - T5 权限守卫双向（secadmin 读/写均 403）；T6 判定预览（先开关后预算，已用量恒 0）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class AiConfigGovernanceIntegrationTest {

    private static final String CONFIG_PATH = "/api/v1/ai-config";
    private static final String SAVE_PATH = "/api/v1/ai-config/save";
    private static final String PREVIEW_PATH = "/api/v1/ai-config/preview";
    private static final String SYSADMIN = "platform.sysadmin";
    private static final String SECADMIN = "platform.secadmin";
    private static final String SUBJECT = "S-W3104-T1-SYSADMIN";

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_aiconfig_it");
        registry.add("ctds.auth.rbac.source", () -> "jdbc");
        registry.add("ctds.auth.rbac.schema", () -> "ctds_evidence_aiconfig_it");
        registry.add("ctds.domain.id", () -> "ctds-demo-domain");
        registry.add("ctds.domain.name", () -> "演示部署域");
        registry.add("ctds.domain.platform-version", () -> "V1.0");
        registry.add("ctds.domain.schema-list", () ->
                "ctds_evidence_aiconfig_it,ctds_subject,ctds_space");
        registry.add("ctds.domain.service-list", () ->
                "evidence-service,subject-service,space-service,gateway-service");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** 用例顺序无关：每用例前重置回 V6 seed 态（清留痕 + 总控/供应商回缺省）。 */
    @BeforeEach
    void resetSeedState() {
        jdbc.update("DELETE FROM ai_gateway_config_change_log");
        jdbc.update("UPDATE ai_gateway_config SET enabled = 0, default_provider = 'glm', "
                + "token_budget = 0, updated_by = NULL WHERE id = 1");
        jdbc.update("UPDATE ai_gateway_provider_config SET base_url = NULL, model = NULL");
    }

    /** T1+T8：缺省视图 = C-11.1 缺省（关闭/glm/0/四供应商未配置）；读响应零密钥字段。 */
    @Test
    void defaultViewMatchesC111DefaultsWithoutAnyKeyField() throws Exception {
        MvcResult result = mockMvc.perform(get(CONFIG_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.defaultProvider").value("glm"))
                .andExpect(jsonPath("$.data.tokenBudget").value(0))
                .andExpect(jsonPath("$.data.providers.length()").value(4))
                .andExpect(jsonPath("$.data.providers[0].providerId").value("glm"))
                .andExpect(jsonPath("$.data.providers[0].configured").value(false))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).as("T8 红线：读响应零密钥字段（api_key_ref 不出站，hifi §1）")
                .doesNotContain("apiKey").doesNotContain("api_key");
    }

    /** T3+T2：保存成功 → 落库 + 返回保存后视图 + 留痕仅变化字段（defaultProvider 未变无行）。 */
    @Test
    void savePersistsAndLogsOnlyChangedFields() throws Exception {
        mockMvc.perform(post(SAVE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"defaultProvider\":\"glm\",\"tokenBudget\":100000,"
                                + "\"providers\":["
                                + "{\"providerId\":\"glm\",\"baseUrl\":\"https://open.example.com/v4\","
                                + "\"model\":\"glm-4-flash\"},"
                                + "{\"providerId\":\"deepseek\",\"baseUrl\":null,\"model\":null},"
                                + "{\"providerId\":\"qwen\",\"baseUrl\":null,\"model\":null},"
                                + "{\"providerId\":\"kimi\",\"baseUrl\":null,\"model\":null}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.tokenBudget").value(100000))
                .andExpect(jsonPath("$.data.providers[0].configured").value(true));

        Map<String, Object> gateway = jdbc.queryForMap(
                "SELECT enabled, default_provider, token_budget, updated_by FROM ai_gateway_config WHERE id = 1");
        assertThat(gateway.get("enabled")).isEqualTo(1);
        assertThat(gateway.get("token_budget")).isEqualTo(100000L);
        assertThat(gateway.get("updated_by")).isEqualTo(SUBJECT);

        final List<String> paths = jdbc.queryForList(
                "SELECT field_path FROM ai_gateway_config_change_log ORDER BY id", String.class);
        assertThat(paths).as("T3：留痕仅变化字段（defaultProvider 未变 → 无行）")
                .containsExactly("enabled", "tokenBudget", "providers.glm.baseUrl", "providers.glm.model");

        // T2：保存后回读 = 已保存值
        mockMvc.perform(get(CONFIG_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.providers[0].model").value("glm-4-flash"));

        // E4：留痕分页端点与库面一致（total = 留痕行数；list 按 id 倒序）
        mockMvc.perform(get("/api/v1/ai-config/changes?pageNum=1&pageSize=10")
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.list[0].fieldPath").value("providers.glm.model"))
                .andExpect(jsonPath("$.data.list[0].afterValue").value("glm-4-flash"));
    }

    /** T3 补：与库值完全一致的重复保存 = 成功但留痕零新行（自备状态，顺序无关）。 */
    @Test
    void identicalSaveAddsNoChangeLogRows() throws Exception {
        final String payload = "{\"enabled\":true,\"defaultProvider\":\"glm\",\"tokenBudget\":100000,"
                + "\"providers\":["
                + "{\"providerId\":\"glm\",\"baseUrl\":\"https://open.example.com/v4\",\"model\":\"glm-4-flash\"},"
                + "{\"providerId\":\"deepseek\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"qwen\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"kimi\",\"baseUrl\":null,\"model\":null}]}";
        mockMvc.perform(post(SAVE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN)
                        .contentType("application/json").content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        long before = countChanges();
        mockMvc.perform(post(SAVE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN)
                        .contentType("application/json").content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        assertThat(countChanges()).as("值未变化 → 零新增留痕（规格 B2-3）").isEqualTo(before);
    }

    /** T4#1：providers 出现清单外编号 → 400 + 1014C0001，库与留痕零变化。 */
    @Test
    void saveRejectsProviderOutsideClosedList() throws Exception {
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":0,"
                + "\"providers\":["
                + "{\"providerId\":\"glm\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"gpt\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"qwen\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"kimi\",\"baseUrl\":null,\"model\":null}]}",
                "供应商编号不在封闭清单内");
    }

    /** T4#2：默认供应商不在清单 → 400 + 1014C0001，零变化。 */
    @Test
    void saveRejectsDefaultProviderOutsideClosedList() throws Exception {
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"gpt\",\"tokenBudget\":0,"
                + defaultProvidersJson() + "}", "默认供应商不在封闭清单内");
    }

    /** T4#3：负预算 → 400 + 1014C0001，零变化。 */
    @Test
    void saveRejectsNegativeBudget() throws Exception {
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":-1,"
                + defaultProvidersJson() + "}", "token 预算须为 ≥0 的整数");
    }

    /** T4#4：非 http(s) 服务地址 → 400 + 1014C0001，零变化。 */
    @Test
    void saveRejectsNonHttpUrl() throws Exception {
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":0,"
                + "\"providers\":["
                + "{\"providerId\":\"glm\",\"baseUrl\":\"ftp://open.example.com\",\"model\":null},"
                + "{\"providerId\":\"deepseek\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"qwen\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"kimi\",\"baseUrl\":null,\"model\":null}]}",
                "服务地址须为 http(s) URL");
    }

    /** T4#5：开关缺失 / null → 400 + 1014C0001（MSG_ENABLED_INVALID），零变化（P2-B：hifi §8 边界行补权威测试）。 */
    @Test
    void saveRejectsMissingOrNullEnabled() throws Exception {
        assertRejected("{\"defaultProvider\":\"glm\",\"tokenBudget\":0," + defaultProvidersJson() + "}",
                "网关开关须为布尔值");
        assertRejected("{\"enabled\":null,\"defaultProvider\":\"glm\",\"tokenBudget\":0,"
                + defaultProvidersJson() + "}", "网关开关须为布尔值");
    }

    /** T4#6：预算缺参（null）→ 400 + 1014C0001（MSG_BUDGET_INVALID），零变化（P2-B）。 */
    @Test
    void saveRejectsMissingBudget() throws Exception {
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":null,"
                + defaultProvidersJson() + "}", "token 预算须为 ≥0 的整数");
    }

    /** T4#7：预算非整数（JSON 数字 1.5）→ 400 + 1014C0001，零变化（P2-A：禁宽泛 float→int 截断静默保存）。 */
    @Test
    void saveRejectsNonIntegerBudgetNumber() throws Exception {
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":1.5,"
                + defaultProvidersJson() + "}", "token 预算须为 ≥0 的整数");
    }

    /** T4#8：预算非整数文本（"1.5"）/ 非数字文本（"abc"）→ 400 + 1014C0001，零变化（P2-A）。 */
    @Test
    void saveRejectsNonIntegerOrNonNumericBudgetText() throws Exception {
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":\"1.5\","
                + defaultProvidersJson() + "}", "token 预算须为 ≥0 的整数");
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":\"abc\","
                + defaultProvidersJson() + "}", "token 预算须为 ≥0 的整数");
    }

    /** T4#9：模型名 129 字符 → 400 + 1014C0001（MSG_MODEL_INVALID），零变化（P2-B：后端权威面）。 */
    @Test
    void saveRejectsModelOver128Chars() throws Exception {
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":0,\"providers\":["
                + "{\"providerId\":\"glm\",\"baseUrl\":null,\"model\":\"" + "x".repeat(129) + "\"},"
                + "{\"providerId\":\"deepseek\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"qwen\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"kimi\",\"baseUrl\":null,\"model\":null}]}",
                "默认模型名不得超过 128 字符");
    }

    /** T4#10：服务地址合法形态但 >512 字符 → 400 + 1014C0001（MSG_BASE_URL_INVALID），零变化（P2-B）。 */
    @Test
    void saveRejectsBaseUrlOver512Chars() throws Exception {
        String longUrl = "https://open.example.com/" + "a".repeat(492);
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":0,\"providers\":["
                + "{\"providerId\":\"glm\",\"baseUrl\":\"" + longUrl + "\",\"model\":null},"
                + "{\"providerId\":\"deepseek\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"qwen\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"kimi\",\"baseUrl\":null,\"model\":null}]}",
                "服务地址须为 http(s) URL");
    }

    /** T4#11：providers 缺行（3 行）/ 重复行（4 行含重复）→ 400 + 1014C0001，零变化（P2-B）。 */
    @Test
    void saveRejectsProvidersMissingOrDuplicatedRows() throws Exception {
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":0,\"providers\":["
                + "{\"providerId\":\"glm\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"deepseek\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"qwen\",\"baseUrl\":null,\"model\":null}]}",
                "供应商编号不在封闭清单内");
        assertRejected("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":0,\"providers\":["
                + "{\"providerId\":\"glm\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"glm\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"deepseek\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"qwen\",\"baseUrl\":null,\"model\":null}]}",
                "供应商编号不在封闭清单内");
    }

    /** T5：无权限双向拒绝（读 403 / 写 403），库与留痕零变化（自备状态，顺序无关）。 */
    @Test
    void secadminIsDeniedForReadAndWrite() throws Exception {
        mockMvc.perform(post(SAVE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"defaultProvider\":\"glm\",\"tokenBudget\":123456,"
                                + defaultProvidersJson() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        Map<String, Object> gatewayBefore = jdbc.queryForMap(
                "SELECT enabled, token_budget FROM ai_gateway_config WHERE id = 1");
        long before = countChanges();
        mockMvc.perform(get(CONFIG_PATH)
                        .header("X-Ctds-Subject", "S-W3104-T5-SECADMIN")
                        .header("X-Ctds-Roles", SECADMIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
        mockMvc.perform(post(SAVE_PATH)
                        .header("X-Ctds-Subject", "S-W3104-T5-SECADMIN")
                        .header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json")
                        .content("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":1,"
                                + defaultProvidersJson() + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
        assertThat(countChanges()).as("越权写零变化").isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT enabled, token_budget FROM ai_gateway_config WHERE id = 1"))
                .as("T5：库值未被越权改动").isEqualTo(gatewayBefore);
    }

    /** T6：判定预览先开关后预算（缺省关闭 → 拒绝；开启+未设限 → 允许）。 */
    @Test
    void previewFollowsSwitchThenBudgetSemantics() throws Exception {
        mockMvc.perform(get(PREVIEW_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("DENIED_DISABLED"))
                .andExpect(jsonPath("$.data.reason").value("拒绝：网关未启用"));
        mockMvc.perform(post(SAVE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN)
                        .contentType("application/json")
                        .content("{\"enabled\":false,\"defaultProvider\":\"glm\",\"tokenBudget\":0,"
                                + defaultProvidersJson() + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(SAVE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"defaultProvider\":\"glm\",\"tokenBudget\":0,"
                                + defaultProvidersJson() + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(PREVIEW_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("ALLOW"))
                .andExpect(jsonPath("$.data.reason").value("允许（当前配置下网关放行）"));
    }

    private String defaultProvidersJson() {
        return "\"providers\":["
                + "{\"providerId\":\"glm\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"deepseek\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"qwen\",\"baseUrl\":null,\"model\":null},"
                + "{\"providerId\":\"kimi\",\"baseUrl\":null,\"model\":null}]";
    }

    private void assertRejected(final String body, final String expectedMessagePart) throws Exception {
        long before = countChanges();
        Map<String, Object> gatewayBefore = jdbc.queryForMap(
                "SELECT enabled, token_budget FROM ai_gateway_config WHERE id = 1");
        mockMvc.perform(post(SAVE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1014C0001"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(expectedMessagePart)));
        assertThat(countChanges()).as("校验拒绝零留痕").isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT enabled, token_budget FROM ai_gateway_config WHERE id = 1"))
                .as("校验拒绝库值零变化").isEqualTo(gatewayBefore);
    }

    private long countChanges() {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_gateway_config_change_log", Long.class);
        return total == null ? 0L : total;
    }
}
