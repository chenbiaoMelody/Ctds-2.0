package com.ctds.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.contract.application.InitiationCheck;
import com.ctds.contract.application.TemplateQueryService;
import com.ctds.contract.domain.SubjectAdmission;
import com.ctds.contract.domain.SubjectAdmissionPort;
import com.ctds.contract.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 合约模板库生命周期集成测试（WBS-3.4.2 hifi §7 T1~T10；规格 C-4.1~4.3 行为 1 七规则 + 五验收标准）：
 * T1 新增（幂等/同名 409）；T2 修订版本化（V2 产生、V1 保留、指针前移、from→to 留痕、幂等重放）；
 * T3 维护权矩阵（admin 过 / provider 与未入驻拒 + DENIED_MANAGE 留痕 / 未认证 401 / 直调同拒）；
 * T4 浏览边界防枚举（未入驻/不存在同文案、UNAVAILABLE 不冒充、停用与不存在同形逐字）；
 * T5 浏览列表与详情（仅启用中、字段集锚定）；T6 启停门槛（重复停用拒、恢复、QV1 联动）；
 * T7 版本历史与留痕查询（运营读面；manage 注解挡）；T8 快照不可变反向探针；
 * T9 条款框架校验（缺必填/未知槽位/对照组）；T10 发起侧校验方法（QV1 三条件反证 + QV2 全文）。
 *
 * <p>真实 MySQL 8 容器实跑 Flyway V1（预置三类模板种子随迁移落库）；资格判定端口 @MockitoBean
 * （沿 catalog 先例）；本机 Docker 未运行时整类跳过（门禁不红）。各用例名称带唯一后缀——
 * 幂等键跨用例零串扰。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ContractTemplateLifecycleIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/contract-templates";
    private static final String MANAGE = BASE + "/manage";
    private static final String ADMIN = "admin";
    private static final String PROVIDER = "provider";
    private static final String CT_PREFIX = "CT";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_lifecycle_it");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TemplateQueryService queryService;
    @MockitoBean
    private SubjectAdmissionPort subjectAdmissionPort;

    @BeforeEach
    void admitAdminByDefault() {
        given(subjectAdmissionPort.check("S-admin")).willReturn(SubjectAdmission.ADMITTED);
    }

    // ==== T1 新增：成功 + 幂等重放 + 同名 409 + 留痕 ====

    @Test
    void t1_adminCreatesTemplateWithV1AndLog() throws Exception {
        final String name = unique("模板A");
        final MvcResult result = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, "PUBLIC_DATA_AUTHORIZATION", publicAuthFramework())))
                .andReturn();
        final JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body.path("code").asText()).isEqualTo("0");
        final String templateNo = body.path("data").path("templateNo").asText();
        assertThat(templateNo).matches("^CT\\d{6}$");
        assertThat(body.path("data").path("currentVersion").asInt()).isEqualTo(1);
        assertThat(body.path("data").path("status").asText()).isEqualTo("ENABLED");
        // 留痕四要素（行为 1 规则 7）：谁/何时/哪个模板+版本/动作
        final var logRow = jdbcTemplate.queryForMap(
                "SELECT action, actor_subject_no, version_no, from_value, to_value, created_at "
                        + "FROM contract_template_action_log WHERE template_no = ? AND action = 'CREATE'",
                templateNo);
        assertThat(logRow.get("action")).isEqualTo("CREATE");
        assertThat(logRow.get("actor_subject_no")).isEqualTo("S-admin");
        assertThat(logRow.get("version_no")).isEqualTo(1);
        assertThat(logRow.get("created_at")).isNotNull();
    }

    @Test
    void t1_idempotentReplayReturnsFirstResultWithoutSecondRow() throws Exception {
        final String name = unique("幂等模板");
        final String body = createBody(name, "API_CALL", apiCallFramework());
        final MvcResult first = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        final Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_template WHERE template_name = ?", Integer.class, name);
        final MvcResult replay = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        final Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_template WHERE template_name = ?", Integer.class, name);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(MAPPER.readTree(replay.getResponse().getContentAsString())
                .path("data").path("templateNo").asText())
                .isEqualTo(MAPPER.readTree(first.getResponse().getContentAsString())
                        .path("data").path("templateNo").asText());
        assertThat(before).isEqualTo(1);
        assertThat(after).isEqualTo(1);
    }

    @Test
    void t1_sameTypeAndNameRejected() throws Exception {
        final String name = unique("重名模板");
        mockMvc.perform(post(BASE).header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, "PUBLIC_DATA_AUTHORIZATION", publicAuthFramework())))
                .andReturn();
        final MvcResult second = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name.toUpperCase(), "PUBLIC_DATA_AUTHORIZATION",
                                publicAuthFramework())))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(MAPPER.readTree(second.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("1008C0005");
    }

    // ==== T2 修订版本化 ====

    @Test
    void t2_revisionProducesV2KeepsV1AndMovesPointer() throws Exception {
        final String templateNo = adminCreate("版本化模板", "API_CALL", apiCallFramework());
        final String revised = apiCallFramework().replace("总量/峰值限制", "总量/峰值限制（修订版）");
        final MvcResult result = mockMvc.perform(post(BASE + "/" + templateNo + "/revisions")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clauseFramework\":" + revised + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        final JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("data").path("versionNo").asInt()).isEqualTo(2);
        assertThat(body.path("data").path("previousVersionNo").asInt()).isEqualTo(1);
        // 旧版本保留可查 + 当前版本指针前移（行为 1 规则 3）
        final var template = jdbcTemplate.queryForMap(
                "SELECT current_version FROM contract_template WHERE template_no = ?", templateNo);
        assertThat(template.get("current_version")).isEqualTo(2);
        final Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_template_version v JOIN contract_template t "
                        + "ON t.id = v.template_id WHERE t.template_no = ?", Integer.class, templateNo);
        assertThat(versions).isEqualTo(2);
        // 修订留痕 from→to 逐字
        final var logRow = jdbcTemplate.queryForMap(
                "SELECT from_value, to_value FROM contract_template_action_log "
                        + "WHERE template_no = ? AND action = 'REVISE'", templateNo);
        assertThat(logRow.get("from_value")).isEqualTo("1");
        assertThat(logRow.get("to_value")).isEqualTo("2");
    }

    @Test
    void t2_revisionIdempotentReplayDoesNotCreateV3() throws Exception {
        final String templateNo = adminCreate("修订幂等模板", "API_CALL", apiCallFramework());
        final String revised = apiCallFramework().replace("总量/峰值限制", "总量/峰值限制（幂等版）");
        final String body = "{\"clauseFramework\":" + revised + "}";
        mockMvc.perform(post(BASE + "/" + templateNo + "/revisions")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        mockMvc.perform(post(BASE + "/" + templateNo + "/revisions")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        final Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_template_version v JOIN contract_template t "
                        + "ON t.id = v.template_id WHERE t.template_no = ?", Integer.class, templateNo);
        assertThat(versions).isEqualTo(2);
    }

    @Test
    void t2_concurrentRevisionHitsUniqueIndexTranslatedToConflict() throws Exception {
        // 并发兜底探针：模拟并发赢家已写入 V2（INSERT 后 AppService 再修订 V2）→ 撞
        // uk_template_version → 转译 1008C0009（沿 catalog T15 并发兜底口径）
        final String templateNo = adminCreate("并发修订模板", "API_CALL", apiCallFramework());
        final Long templateId = jdbcTemplate.queryForObject(
                "SELECT id FROM contract_template WHERE template_no = ?", Long.class, templateNo);
        jdbcTemplate.update("INSERT INTO contract_template_version (template_id, version_no, "
                + "clause_framework, published_by) VALUES (?, 2, ?, 'other-admin')", templateId, "{}");
        final MvcResult result = mockMvc.perform(post(BASE + "/" + templateNo + "/revisions")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clauseFramework\":" + apiCallFramework() + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(MAPPER.readTree(result.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("1008C0009");
    }

    // ==== T3 维护权矩阵（行为 1 规则 1；剧本 S3-1/S3-2）====

    @Test
    void t3_providerMaintainDeniedWithLog() throws Exception {
        given(subjectAdmissionPort.check("S-provider")).willReturn(SubjectAdmission.ADMITTED);
        final MvcResult result = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-provider").header("X-Ctds-Roles", PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unique("越权模板"), "API_CALL", apiCallFramework())))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        final JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("1008C0002");
        assertThat(body.path("message").asText()).isEqualTo("无权进行模板维护操作");
        // 拒绝留痕（规则 1"一律拒绝并留痕"；模板定位前拒绝 → template_no NULL）
        final Integer denied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_template_action_log WHERE action = 'DENIED_MANAGE' "
                        + "AND actor_subject_no = 'S-provider' AND reason_code = 'C0002'", Integer.class);
        assertThat(denied).isEqualTo(1);
    }

    @Test
    void t3_directApiCallVariantAlsoDeniedWithLog() throws Exception {
        // 剧本 S3-1"含直接调用接口绕过界面的技术侧变体"——直调（无界面会话差异）同走服务端强制
        given(subjectAdmissionPort.check("S-direct")).willReturn(SubjectAdmission.ADMITTED);
        final MvcResult result = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-direct").header("X-Ctds-Roles", PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unique("直调越权模板"), "API_CALL", apiCallFramework())))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        final Integer denied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_template_action_log WHERE action = 'DENIED_MANAGE' "
                        + "AND actor_subject_no = 'S-direct'", Integer.class);
        assertThat(denied).isEqualTo(1);
    }

    @Test
    void t3_notAdmittedMaintainDeniedWithUnifiedMessage() throws Exception {
        // 剧本 S3-2：未入驻主体维护 → 统一业务文案（防枚举：不区分主体不存在/未入驻）
        given(subjectAdmissionPort.check("S-pending")).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult result = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-pending").header("X-Ctds-Roles", PROVIDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unique("未入驻模板"), "API_CALL", apiCallFramework())))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        final JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("1008C0003");
        assertThat(body.path("message").asText()).isEqualTo("主体未入驻或不存在，无法维护模板");
        final Integer denied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_template_action_log WHERE action = 'DENIED_MANAGE' "
                        + "AND actor_subject_no = 'S-pending' AND reason_code = 'C0003'", Integer.class);
        assertThat(denied).isEqualTo(1);
    }

    @Test
    void t3_unauthenticatedRequestRejectedAtAnnotationLayer() throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unique("无头模板"), "API_CALL", apiCallFramework())))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void t3_subjectServiceUnavailableNotDisguisedAsDenied() throws Exception {
        given(subjectAdmissionPort.check("S-admin")).willReturn(SubjectAdmission.UNAVAILABLE);
        final MvcResult result = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unique("不可用模板"), "API_CALL", apiCallFramework())))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(MAPPER.readTree(result.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("1008S0001");
    }

    // ==== T4 浏览边界防枚举（行为 1 规则 5；剧本 S1-3）====

    @Test
    void t4_notAdmittedBrowseRejectedWithUnifiedMessage() throws Exception {
        given(subjectAdmissionPort.check("S-pending")).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult listResult = mockMvc.perform(get(BASE + "?pageSize=100")
                        .header("X-Ctds-Subject", "S-pending").header("X-Ctds-Roles", PROVIDER))
                .andReturn();
        assertThat(listResult.getResponse().getStatus()).isEqualTo(404);
        final JsonNode listBody = MAPPER.readTree(listResult.getResponse().getContentAsString());
        assertThat(listBody.path("code").asText()).isEqualTo("1008C0003");
        assertThat(listBody.path("message").asText()).isEqualTo("主体未入驻或不存在，无法浏览模板");
        // 浏览详情同口径
        final MvcResult detail = mockMvc.perform(get(BASE + "/CT000001")
                        .header("X-Ctds-Subject", "S-pending").header("X-Ctds-Roles", PROVIDER))
                .andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(404);
        assertThat(MAPPER.readTree(detail.getResponse().getContentAsString()).path("message").asText())
                .isEqualTo("主体未入驻或不存在，无法浏览模板");
    }

    @Test
    void t4_browseUnavailableNotDisguisedAsDenied() throws Exception {
        given(subjectAdmissionPort.check("S-x")).willReturn(SubjectAdmission.UNAVAILABLE);
        final MvcResult result = mockMvc.perform(get(BASE + "?pageSize=100")
                        .header("X-Ctds-Subject", "S-x").header("X-Ctds-Roles", PROVIDER))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(MAPPER.readTree(result.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("1008S0001");
    }

    @Test
    void t4_disabledTemplateIndistinguishableFromMissing() throws Exception {
        // Q6-A 最严口径：停用模板对浏览者 = 不存在（同码同文案逐字相同——防枚举可证伪探针）
        final String templateNo = adminCreate("停用同形模板", "PUBLIC_DATA_AUTHORIZATION",
                publicAuthFramework());
        mockMvc.perform(post(BASE + "/" + templateNo + "/disable")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        final MvcResult disabled = mockMvc.perform(get(BASE + "/" + templateNo)
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        final MvcResult missing = mockMvc.perform(get(BASE + "/CT999999")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        assertThat(disabled.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(disabled.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString());
        assertThat(MAPPER.readTree(disabled.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("1008C0001");
    }

    // ==== T5 浏览列表与详情（剧本 S1-1/S1-2）====

    @Test
    void t5_browseListsOnlyEnabledAndDetailCarriesFramework() throws Exception {
        final String disabledNo = adminCreate("列表过滤停用", "PRIVACY_COMPUTING",
                privacyComputingFramework());
        mockMvc.perform(post(BASE + "/" + disabledNo + "/disable")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        final MvcResult list = mockMvc.perform(get(BASE + "?pageSize=100")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        final JsonNode body = MAPPER.readTree(list.getResponse().getContentAsString());
        final JsonNode rows = body.path("data").path("list");
        assertThat(rows.toString()).doesNotContain("列表过滤停用");
        // 预置三类模板（种子）均启用中可见
        assertThat(rows.toString()).contains("CT000001").contains("CT000002").contains("CT000003");
        // 列表项字段集锚定：不含状态/审计列（hifi §2.3 契约）
        final JsonNode firstRow = rows.get(0);
        assertThat(firstRow.has("status")).isFalse();
        assertThat(firstRow.has("createdBy")).isFalse();
        assertThat(firstRow.has("templateNo")).isTrue();
        // 详情 = 当前版本条款框架全文；不含数据本体与敏感原文（响应字段集锚定）
        final MvcResult detail = mockMvc.perform(get(BASE + "/CT000002")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        final JsonNode detailBody = MAPPER.readTree(detail.getResponse().getContentAsString());
        assertThat(detailBody.path("data").path("templateNo").asText()).isEqualTo("CT000002");
        assertThat(detailBody.path("data").path("clauseFramework").path("slots").isArray()).isTrue();
        assertThat(detailBody.path("data").path("clauseFramework").path("slots").size()).isEqualTo(10);
        assertThat(detailBody.path("data").has("status")).isFalse();
    }

    // ==== T6 启停门槛（行为 1 规则 4；剧本 S2-4/S2-6）====

    @Test
    void t6_disableRemovesFromBrowseAndBlocksInitiationThenEnableRestores() throws Exception {
        final String templateNo = adminCreate("启停模板", "PUBLIC_DATA_AUTHORIZATION", publicAuthFramework());
        // 停用：退出浏览列表 + QV1 判"已停用"
        mockMvc.perform(post(BASE + "/" + templateNo + "/disable")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        final MvcResult list = mockMvc.perform(get(BASE + "?pageSize=100")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        assertThat(list.getResponse().getContentAsString()).doesNotContain(templateNo);
        final InitiationCheck check = queryService.validateForInitiation(templateNo, 1);
        assertThat(check.valid()).isFalse();
        assertThat(check.invalidReason()).isEqualTo(InitiationCheck.InitiationInvalidReason.TEMPLATE_DISABLED);
        // 停留痕 from→to
        final var disableLog = jdbcTemplate.queryForMap(
                "SELECT from_value, to_value FROM contract_template_action_log "
                        + "WHERE template_no = ? AND action = 'DISABLE'", templateNo);
        assertThat(disableLog.get("from_value")).isEqualTo("ENABLED");
        assertThat(disableLog.get("to_value")).isEqualTo("DISABLED");
        // 重复停用 → 409 C0007 + DENIED_MANAGE 留痕（状态机门槛）
        final MvcResult again = mockMvc.perform(post(BASE + "/" + templateNo + "/disable")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(MAPPER.readTree(again.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("1008C0007");
        final Integer deniedState = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_template_action_log WHERE template_no = ? "
                        + "AND action = 'DENIED_MANAGE' AND reason_code = 'C0007'", Integer.class, templateNo);
        assertThat(deniedState).isEqualTo(1);
        // 重新启用：恢复可浏览 + QV1 恢复有效（剧本 S2-6）
        mockMvc.perform(post(BASE + "/" + templateNo + "/enable")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        final MvcResult restored = mockMvc.perform(get(BASE + "?pageSize=100")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        assertThat(restored.getResponse().getContentAsString()).contains(templateNo);
        assertThat(queryService.validateForInitiation(templateNo, 1).valid()).isTrue();
        final Integer enableLog = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_template_action_log WHERE template_no = ? "
                        + "AND action = 'ENABLE'", Integer.class, templateNo);
        assertThat(enableLog).isEqualTo(1);
    }

    // ==== T7 版本历史与留痕查询（行为 1 规则 3/7；剧本 S3-3）====

    @Test
    void t7_versionHistoryKeepsOldVersionsWithFullFramework() throws Exception {
        final String templateNo = adminCreate("历史模板", "PUBLIC_DATA_AUTHORIZATION", publicAuthFramework());
        mockMvc.perform(post(BASE + "/" + templateNo + "/revisions")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clauseFramework\":" + publicAuthFramework()
                                .replace("更新频率", "更新频率V2") + "}"))
                .andReturn();
        final MvcResult history = mockMvc.perform(get(MANAGE + "/" + templateNo + "/versions")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        assertThat(history.getResponse().getStatus()).isEqualTo(200);
        final JsonNode body = MAPPER.readTree(history.getResponse().getContentAsString());
        final JsonNode rows = body.path("data");
        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0).path("versionNo").asInt()).isEqualTo(1);
        assertThat(rows.get(1).path("versionNo").asInt()).isEqualTo(2);
        assertThat(rows.get(0).path("clauseFramework").path("slots").size()).isEqualTo(9);
        assertThat(rows.get(1).path("publishedBy").asText()).isEqualTo("S-admin");
    }

    @Test
    void t7_manageReadBlockedForProviderWithoutLog() throws Exception {
        // R1~R3 运营读面 = manage 点（仅 admin 持有）注解挡；读面拒绝无规格留痕义务（hifi Q8-A）
        given(subjectAdmissionPort.check("S-provider")).willReturn(SubjectAdmission.ADMITTED);
        final MvcResult result = mockMvc.perform(get(MANAGE)
                        .header("X-Ctds-Subject", "S-provider").header("X-Ctds-Roles", PROVIDER))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void t7_actionLogsCarryFourElementsWithoutSensitiveContent() throws Exception {
        final String templateNo = adminCreate("留痕模板", "API_CALL", apiCallFramework());
        mockMvc.perform(post(BASE + "/" + templateNo + "/disable")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        final MvcResult logs = mockMvc.perform(get(MANAGE + "/action-logs?templateNo=" + templateNo)
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        assertThat(logs.getResponse().getStatus()).isEqualTo(200);
        final JsonNode rows = MAPPER.readTree(logs.getResponse().getContentAsString()).path("data").path("list");
        assertThat(rows.size()).isGreaterThanOrEqualTo(2);
        // 留痕只插不改 + 四要素齐备（谁/何时/哪个模板+版本/动作）+ 不含条款框架全文
        for (final JsonNode row : rows) {
            assertThat(row.path("actorSubjectNo").asText()).isNotBlank();
            assertThat(row.path("createdAt").isNull()).isFalse();
            assertThat(row.path("templateNo").asText()).isEqualTo(templateNo);
            assertThat(row.has("clauseFramework")).isFalse();
        }
        assertThat(rows.toString()).contains("\"action\":\"CREATE\"").contains("\"action\":\"DISABLE\"");
    }

    // ==== T8 快照不可变反向探针（行为 1 规则 3）====

    @Test
    void t8_v1FrameworkUnchangedAfterRevision() throws Exception {
        final String templateNo = adminCreate("快照不可变模板", "PUBLIC_DATA_AUTHORIZATION",
                publicAuthFramework());
        final String v1Framework = jdbcTemplate.queryForObject(
                "SELECT v.clause_framework FROM contract_template_version v "
                        + "JOIN contract_template t ON t.id = v.template_id "
                        + "WHERE t.template_no = ? AND v.version_no = 1", String.class, templateNo);
        mockMvc.perform(post(BASE + "/" + templateNo + "/revisions")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clauseFramework\":" + publicAuthFramework()
                                .replace("起止时间", "起止时间V2") + "}"))
                .andReturn();
        final String v1After = jdbcTemplate.queryForObject(
                "SELECT v.clause_framework FROM contract_template_version v "
                        + "JOIN contract_template t ON t.id = v.template_id "
                        + "WHERE t.template_no = ? AND v.version_no = 1", String.class, templateNo);
        // 版本行不可变：修订后 V1 内容逐字不变（仓储接口无更新路径为编译期保证——hifi §6）
        assertThat(v1After).isEqualTo(v1Framework);
    }

    // ==== T9 条款框架校验（行为 1 规则 6 前置）====

    @Test
    void t9_missingRequiredSlotAndUnknownKeyRejected() throws Exception {
        final String missingRequired = publicAuthFramework().replace(
                ",{\"key\":\"data_format_delivery\",\"name\":\"数据格式与交付方式\",\"required\":true,\"guide\":\"g\"}",
                "");
        final MvcResult missing = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unique("缺槽模板"), "PUBLIC_DATA_AUTHORIZATION", missingRequired)))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(MAPPER.readTree(missing.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("1008C0004");
        final String unknownKey = publicAuthFramework().replace("]}",
                ",{\"key\":\"unknown_slot\",\"name\":\"未知\",\"required\":false,\"guide\":\"g\"}]}");
        final MvcResult unknown = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unique("未知槽模板"), "PUBLIC_DATA_AUTHORIZATION", unknownKey)))
                .andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(MAPPER.readTree(unknown.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("1008C0004");
    }

    // ==== T10 发起侧校验方法（QV1/QV2，供 3.4.3 同宿主直调）====

    @Test
    void t10_initiationValidationCoversThreeInvalidConditions() throws Exception {
        final String templateNo = adminCreate("发起校验模板", "API_CALL", apiCallFramework());
        // 有效：存在 + 版本存在 + 启用中
        assertThat(queryService.validateForInitiation(templateNo, 1).valid()).isTrue();
        // 反证一：模板不存在
        assertThat(queryService.validateForInitiation("CT999998", 1).invalidReason())
                .isEqualTo(InitiationCheck.InitiationInvalidReason.TEMPLATE_NOT_FOUND);
        // 反证二：版本不存在
        assertThat(queryService.validateForInitiation(templateNo, 9).invalidReason())
                .isEqualTo(InitiationCheck.InitiationInvalidReason.VERSION_NOT_FOUND);
        // 反证三：已停用（停用模板不可用于新发起——发起侧拒绝的兑现归 3.4.3）
        mockMvc.perform(post(BASE + "/" + templateNo + "/disable")
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN))
                .andReturn();
        assertThat(queryService.validateForInitiation(templateNo, 1).invalidReason())
                .isEqualTo(InitiationCheck.InitiationInvalidReason.TEMPLATE_DISABLED);
        // QV2 版本快照读取（3.4.3 锁定快照时的内容来源）
        assertThat(queryService.loadFramework(templateNo, 1).orElseThrow().clauseFrameworkJson())
                .contains("rate_limit");
    }

    // ==== 测试支撑 ====

    /** admin 建模板并返回模板编号（用例间独立数据；名称唯一防幂等键串扰）。 */
    private String adminCreate(final String name, final String type, final String framework)
            throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, type, framework)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("admin 建模板应成功：%s", name).isEqualTo(201);
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .path("data").path("templateNo").asText();
    }

    private String createBody(final String name, final String type, final String framework) {
        return "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"clauseFramework\":" + framework + "}";
    }

    private String unique(final String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 合法框架·公共数据授权（9 槽位 = 公共 7 + 差异化 2；可选槽位缺省——对照组）。 */
    private String publicAuthFramework() {
        return commonSlots() + diffSlot("data_format_delivery", "数据格式与交付方式")
                + diffSlot("data_update_obligation", "数据更新与维护义务") + "]}";
    }

    /** 合法框架·API 调用（9 槽位；测试锚 t2/t7 依赖"rate_limit"键）。 */
    private String apiCallFramework() {
        return commonSlots() + diffSlot("api_scope_and_invocation", "接口范围与调用方式")
                + diffSlot("rate_limit", "调用频次上限：总量/峰值限制") + "]}";
    }

    /** 合法框架·隐私计算（10 槽位 = 公共 7 + 差异化 3）。 */
    private String privacyComputingFramework() {
        return commonSlots() + diffSlot("compute_env_security", "计算环境与安全要求")
                + diffSlot("result_delivery", "计算结果交付方式")
                + diffSlot("raw_data_not_leaving_domain", "原始数据不出域承诺") + "]}";
    }

    /** 公共骨架 7 必填槽位（lofi §3.1；guide 用占位短文案——框架校验只看结构与键集合）。 */
    private String commonSlots() {
        final StringBuilder sb = new StringBuilder("{\"slots\":[");
        final String[] common = {"subject_matter", "scope", "term", "purpose_and_restrictions",
            "security_confidentiality", "liability", "dispute_resolution"};
        for (int i = 0; i < common.length; i++) {
            sb.append(String.format("{\"key\":\"%s\",\"name\":\"n-%s\",\"required\":true,\"guide\":\"g\"}",
                    common[i], common[i]));
            if (i < common.length - 1) {
                sb.append(",");
            }
        }
        return sb.toString();
    }

    private String diffSlot(final String key, final String name) {
        return String.format(",{\"key\":\"%s\",\"name\":\"%s\",\"required\":true,\"guide\":\"g\"}", key, name);
    }
}
