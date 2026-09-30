package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ctds.catalog.domain.ActionResult;
import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.Dataset;
import com.ctds.catalog.domain.DatasetActionLog;
import com.ctds.catalog.domain.DatasetRepository;
import com.ctds.catalog.domain.DatasetStatus;
import com.ctds.catalog.domain.DatasetType;
import com.ctds.catalog.domain.DeclareLevel;
import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import com.ctds.catalog.support.SharedMySqlContainer;
import com.ctds.common.errorcode.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 资源登记模型与服务全链集成测试（规格 C-3.1 行为 1/2 + 行为 7 资源侧验收标准；
 * WBS-3.3.2 hifi §6 T1~T10，ADR-010 容器化基座）：登记正向与数据标识/资格防枚举/空间状态与
 * 成员门槛/重要数据拒收（含反向探针）/归一化判重（含 U+3000 绕过）/幂等/变更与只能收紧/
 * 注销两写与名称锁定/终态与越权矩阵/读面防枚举与分页。
 *
 * <p>跨服务判定端口 @MockitoBean（client 三态映射由 SubjectAdmissionClientTest /
 * SpaceMembershipClientTest 覆盖）；本机 Docker 未运行时整类跳过（门禁不红）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogDatasetLifecycleIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SPACES = "/api/v1/data-spaces";
    private static final String DATASETS = "/api/v1/datasets";
    private static final String CONTENT_TYPE = MediaType.APPLICATION_JSON_VALUE;

    private static Path auditDir;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_it");
    }

    @DynamicPropertySource
    static void auditProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.audit.file-dir", () -> auditDir.toString());
    }

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-catalog");
    }

    @AfterAll
    static void deleteAuditDir() throws Exception {
        try (Stream<Path> paths = Files.walk(auditDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** 直连仓储：并发登记窗口（唯一索引兜底）用例绕过应用层预检，验证其真实生效。 */
    @Autowired
    private DatasetRepository datasetRepository;

    @MockitoBean
    private SubjectAdmissionPort admissionPort;

    @MockitoBean
    private SpaceMembershipPort spaceMembershipPort;

    @BeforeEach
    void defaultsAdmittedAndActiveMember() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("ACTIVE", "MEMBER"));
    }

    // ==== T1 登记正向（行为 1 GWT-1/规则 5/规则 7）====

    @Test
    void registerHappyPathGeneratesDataNoAndLog() throws Exception {
        final MvcResult result = register("owner-t1", 1001L, registerBody("普惠金融数据集", ""));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode payload = payload(result);
        // 行为 1 规则 5 + Q3-A：数据标识 = DS + yyyyMMdd + 6 位当日序号，平台生成
        assertThat(payload.path("dataNo").asText()).matches("^DS\\d{14}$");
        assertThat(payload.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(payload.path("declareImportant").asBoolean()).isFalse();
        // 行为 7 规则 5：响应仅目录元数据，不含数据本体字段（字段集显式锚定）
        assertThat(payload.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("id", "dataNo", "spaceId", "name", "type", "intro",
                        "tags", "declareCategory", "declareLevel", "declareImportant", "status",
                        "createdAt");
        // 行为 1 规则 7：登记留痕四要素（谁/何时/对象/动作）+ 结果
        final Integer logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE space_id = 1001 AND dataset_id = ? "
                        + "AND action = 'REGISTER' AND result = 'SUCCESS' AND actor_subject_no = 'owner-t1' "
                        + "AND to_value = 'ACTIVE' AND created_at IS NOT NULL",
                Integer.class, payload.path("id").asLong());
        assertThat(logs).isEqualTo(1);
    }

    // ==== T2 资格防枚举（行为 1 GWT-2 / 规则 1）====

    @Test
    void notAdmittedUsesUnifiedMessageAndUnavailableNeverMasquerades() throws Exception {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult denied = register("owner-t2", 1002L, registerBody("资格数据集", ""));
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(denied)).isEqualTo("1007C0006");
        // 未入驻与"主体不存在"统一文案（防枚举）
        assertThat(root(denied).path("message").asText()).isEqualTo("主体未入驻或不存在，无法登记资源");
        // 防枚举同形双路对照：另一主体（主体不存在，客户端侧归 NOT_ADMITTED）与未入驻的响应码与文案
        // 逐字相同。**证据链说明**：本类以 @MockitoBean 表达资格端口，两路共用"NOT_ADMITTED 单一分支"，
        // 同形由"客户端边界把两类上游答案归并为一态 + 应用层单一拒绝分支"共同保证——前者的独立可证伪
        // 证据见 SubjectAdmissionClientTest#notFoundAndNotAdmittedAreIndistinguishableAtClientBoundary
        final MvcResult absent = register("owner-t2-absent", 1002L, registerBody("资格数据集", ""));
        assertThat(absent.getResponse().getStatus()).isEqualTo(denied.getResponse().getStatus());
        assertThat(codeOf(absent)).isEqualTo(codeOf(denied));
        assertThat(root(absent).path("message").asText())
                .isEqualTo(root(denied).path("message").asText());
        given(admissionPort.check(any())).willReturn(SubjectAdmission.UNAVAILABLE);
        final MvcResult unavailable = register("owner-t2", 1002L, registerBody("资格数据集", ""));
        assertThat(unavailable.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(unavailable)).isEqualTo("1007S0001");
    }

    // ==== T3 空间门槛（行为 1 GWT-3/4 / 规则 1/2）====

    @Test
    void spaceStateGateRejectsAllNonActiveStates() throws Exception {
        for (final String status : new String[] {"CREATED", "FROZEN", "DISSOLVED", "NONE"}) {
            given(spaceMembershipPort.check(anyLong(), any())).willReturn(SpaceMembership.of(status, "MEMBER"));
            final MvcResult result = register("owner-t3", 1003L, registerBody("状态门槛" + status, ""));
            assertThat(result.getResponse().getStatus()).as(status).isEqualTo(409);
            assertThat(codeOf(result)).as(status).isEqualTo("1007C0002");
        }
    }

    @Test
    void nonMemberDeniedWithLogAndSpaceServiceUnavailableDistinct() throws Exception {
        // 非成员（ACTIVE 空间 + role NONE）→ 权限拒绝 + DENIED 留痕
        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("ACTIVE", SpaceMembership.ROLE_NONE));
        final MvcResult denied = register("outsider-t3", 1004L, registerBody("非成员数据集", ""));
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(denied)).isEqualTo("1007C0006");
        final Integer deniedLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE space_id = 1004 AND action = 'DENIED_REGISTER' "
                        + "AND result = 'DENIED' AND actor_subject_no = 'outsider-t3' "
                        + "AND reason_code = 'C0006'",
                Integer.class);
        assertThat(deniedLogs).isEqualTo(1);
        // 空间服务不可达 → 1007S0002，不冒充"非成员/空间不存在"
        given(spaceMembershipPort.check(anyLong(), any())).willReturn(SpaceMembership.unavailable());
        final MvcResult unavailable = register("outsider-t3", 1004L, registerBody("非成员数据集2", ""));
        assertThat(unavailable.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(unavailable)).isEqualTo("1007S0002");
    }

    // ==== T4 重要数据拒收（行为 1 GWT-5 / 规则 4；反向探针）====

    @Test
    void importantDeclarationAlwaysRejectedWithLog() throws Exception {
        final MvcResult rejected = register("owner-t4", 1005L,
                registerBody("重要数据数据集", ",\"declareImportant\":true"));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(rejected)).isEqualTo("1007C0003");
        // 反向探针：拒收必留痕，且未落任何资源行（硬约束不被绕过）
        final Integer deniedLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE space_id = 1005 "
                        + "AND action = 'DENIED_REGISTER' AND reason_code = 'C0003'",
                Integer.class);
        assertThat(deniedLogs).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset WHERE space_id = 1005",
                Integer.class)).isZero();
    }

    @Test
    void importantDeclarationBypassAttemptsAllRejected() throws Exception {
        // 探针①数值 1（Jackson 布尔强转）→ 仍被判为重要数据并拒收；②字符串 "TRUE"（大小写变体强转）
        // → 同样拒收（fail-closed：任何真值申报都不放行）；③显式 false → 放行（对照组：不误伤正常申报）
        final MvcResult numeric = register("owner-t4b", 1006L,
                registerBody("绕过数据集1", ",\"declareImportant\":1"));
        assertThat(numeric.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(numeric)).isEqualTo("1007C0003");
        final MvcResult textual = register("owner-t4b", 1006L,
                registerBody("绕过数据集2", ",\"declareImportant\":\"TRUE\""));
        assertThat(textual.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(textual)).isEqualTo("1007C0003");
        final MvcResult normal = register("owner-t4b", 1006L,
                registerBody("正常数据集", ",\"declareImportant\":false"));
        assertThat(normal.getResponse().getStatus()).isEqualTo(200);
    }

    // ==== T5 名称与归一化（行为 1 GWT-6 / 规则 5）====

    @Test
    void normalizedDuplicateRejectedIncludingIdeographicSpaceBypass() throws Exception {
        assertThat(register("owner-t5", 1007L, registerBody("普惠金融数据集T5", "")).getResponse().getStatus())
                .isEqualTo(200);
        // 判重拒绝路径换用不同登记主体（绕开幂等结果缓存——同请求键窗口内复用首结果，见 T6）
        final MvcResult padded = register("other-t5", 1007L, registerBody("  普惠金融数据集T5  ", ""));
        assertThat(padded.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(padded)).isEqualTo("1007C0001");
        // U+3000 全角空格绕过尝试（尾部）：显式空白集归一化后同名 → 拒绝
        final MvcResult ideographic = register("third-t5", 1007L,
                registerBody("普惠金融数据集T5\u3000", ""));
        assertThat(ideographic.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(ideographic)).isEqualTo("1007C0001");
        // 跨空间同名允许（不加严：唯一性口径 = 同空间）
        assertThat(register("owner-t5", 1008L, registerBody("普惠金融数据集T5", "")).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void invalidNamesRejected() throws Exception {
        // 名称为空 / 纯空白 / 全控制字符（归一化后为空）→ 1007C0008；超长 → 1007C0008
        assertThat(codeOf(register("owner-t5c", 1009L, registerBody("", "")))).isEqualTo("1007C0008");
        assertThat(codeOf(register("owner-t5c", 1009L, registerBody("   ", "")))).isEqualTo("1007C0008");
        assertThat(codeOf(register("owner-t5c", 1009L, registerBody("\\u0001\\u0002", ""))))
                .isEqualTo("1007C0008");
        assertThat(codeOf(register("owner-t5c", 1009L, registerBody("长".repeat(129), ""))))
                .isEqualTo("1007C0008");
    }

    // ==== T6 幂等（行为 1 GWT-7 / 规则 6）====

    @Test
    void repeatedSameRequestIsIdempotent() throws Exception {
        final MvcResult first = register("owner-t6", 1010L, registerBody("幂等数据集", ""));
        final String firstDataNo = payload(first).path("dataNo").asText();
        final MvcResult replay = register("owner-t6", 1010L, registerBody("幂等数据集", ""));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(payload(replay).path("dataNo").asText()).isEqualTo(firstDataNo);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset WHERE space_id = 1010", Integer.class))
                .isEqualTo(1);
    }

    // ==== T7 变更（行为 2 GWT-1/2 / 规则 1）====

    @Test
    void updateChangesFieldsAndOnlyTightensLevel() throws Exception {
        final long datasetId = registerOk("owner-t7", 1011L, "变更数据集", ",\"declareLevel\":\"L2\"");
        final MvcResult updated = update("owner-t7", datasetId,
                "{\"intro\":\"新简介\",\"tags\":[\"金融\",\"风控\"]}");
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(payload(updated).path("intro").asText()).isEqualTo("新简介");
        // 留痕含"从何值 → 到何值"
        final Integer introLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? AND action = 'UPDATE' "
                        + "AND result = 'SUCCESS' AND from_value = 'intro:资源简介' AND to_value = ?",
                Integer.class, datasetId, "intro:新简介");
        assertThat(introLogs).isEqualTo(1);
        // 级别上调（收紧）通过
        final MvcResult tightened = update("owner-t7", datasetId, "{\"declareLevel\":\"L3\"}");
        assertThat(tightened.getResponse().getStatus()).isEqualTo(200);
        assertThat(payload(tightened).path("declareLevel").asText()).isEqualTo("L3");
        // 级别下调（放宽）拒绝 + DENIED 留痕
        final MvcResult loosened = update("owner-t7", datasetId, "{\"declareLevel\":\"L2\"}");
        assertThat(loosened.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(loosened)).isEqualTo("1007C0004");
        final Integer deniedLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? AND action = 'DENIED_UPDATE' "
                        + "AND result = 'DENIED' AND reason_code = 'C0004' AND from_value = 'L3' "
                        + "AND to_value = 'L2'",
                Integer.class, datasetId);
        assertThat(deniedLogs).isEqualTo(1);
        // 空变更（无任何可变更字段）→ 400 通用参数码
        assertThat(update("owner-t7", datasetId, "{}").getResponse().getStatus()).isEqualTo(400);
    }

    // ==== T8 注销（行为 2 GWT-3 / 规则 2/3）====

    @Test
    void cancellationRequiresConfirmWritesLockAndBlocksNameReuse() throws Exception {
        final long datasetId = registerOk("owner-t8", 1012L, "待注销数据集", "");
        // 二次确认缺失 → 400 通用参数码（confirmCancellation 缺省/false 一律拒绝）
        final MvcResult noConfirm = cancel("owner-t8", datasetId, "{\"confirmCancellation\":false}");
        assertThat(noConfirm.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status FROM dataset WHERE id = ?", String.class, datasetId))
                .isEqualTo("ACTIVE");
        // 二次确认通过 → 两写事务（状态终态 + 名称同空间锁定）
        final MvcResult cancelled = cancel("owner-t8", datasetId, "{\"confirmCancellation\":true}");
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(payload(cancelled).path("status").asText()).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_name_lock WHERE space_id = 1012 "
                + "AND normalized_name = '待注销数据集'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? "
                + "AND action = 'CANCEL' AND from_value = 'ACTIVE' AND to_value = 'DELETED'",
                Integer.class, datasetId)).isEqualTo(1);
        // 注销后同名登记被拒（幂等命中同请求键亦按名称锁口径拒绝——DB-28 同族）
        final MvcResult reused = register("owner-t8", 1012L, registerBody("待注销数据集", ""));
        assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(reused)).isEqualTo("1007C0001");
    }

    // ==== T9 终态与越权矩阵（行为 2 GWT-4/5 / 规则 4/5；行为 7 规则 1/4）====

    @Test
    void deletedTerminalRejectsFurtherActions() throws Exception {
        final long datasetId = registerOk("owner-t9", 1013L, "终态数据集", "");
        cancel("owner-t9", datasetId, "{\"confirmCancellation\":true}");
        final MvcResult update = update("owner-t9", datasetId, "{\"intro\":\"终态后变更\"}");
        assertThat(update.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(update)).isEqualTo("1007C0007");
        final MvcResult recancel = cancel("owner-t9", datasetId, "{\"confirmCancellation\":true}");
        assertThat(recancel.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(recancel)).isEqualTo("1007C0007");
    }

    @Test
    void nonOwnerIncludingSpaceAdminDeniedWithLogs() throws Exception {
        final long datasetId = registerOk("owner-t9b", 1014L, "越权数据集", "");
        // 空间管理员（非登记主体）变更 / 注销一律拒绝 + DENIED 留痕
        final MvcResult update = update("admin-t9b", datasetId, "{\"intro\":\"越权变更\"}");
        assertThat(update.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(update)).isEqualTo("1007C0006");
        final MvcResult cancel = cancel("admin-t9b", datasetId, "{\"confirmCancellation\":true}");
        assertThat(cancel.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(cancel)).isEqualTo("1007C0006");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? "
                + "AND actor_subject_no = 'admin-t9b' AND result = 'DENIED' AND reason_code = 'C0006' "
                + "AND action IN ('DENIED_UPDATE','DENIED_CANCEL')", Integer.class, datasetId)).isEqualTo(2);
        // 资源本体未被改动
        assertThat(jdbc.queryForObject("SELECT intro FROM dataset WHERE id = ?", String.class, datasetId))
                .isEqualTo("资源简介");
    }

    // ==== T10 读面防枚举与分页（行为 7 GWT-2/3 / 规则 1/2）====

    @Test
    void mineListsOnlyOwnDatasetsWithPaginationFields() throws Exception {
        registerOk("owner-t10", 1015L, "我的数据集一", "");
        registerOk("owner-t10", 1015L, "我的数据集二", "");
        registerOk("other-t10", 1015L, "他人数据集", "");
        final MvcResult page = mockMvc.perform(auth(get(DATASETS + "/mine").param("pageNum", "1")
                .param("pageSize", "10"), "owner-t10", "provider")).andReturn();
        final JsonNode payload = payload(page);
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(payload.path("total").asLong()).isEqualTo(2);
        assertThat(payload.path("pageNum").asInt()).isEqualTo(1);
        assertThat(payload.path("pageSize").asInt()).isEqualTo(10);
        assertThat(payload.path("list")).hasSize(2);
        // 空间过滤为可选参数：按本空间命中
        final MvcResult filtered = mockMvc.perform(auth(get(DATASETS + "/mine").param("spaceId", "1015"),
                "owner-t10", "provider")).andReturn();
        assertThat(payload(filtered).path("total").asLong()).isEqualTo(2);
    }

    @Test
    void detailIsOwnerOnlyAndSameShapeAsNotFound() throws Exception {
        final long datasetId = registerOk("owner-t10b", 1016L, "详情数据集", "");
        final MvcResult mine = mockMvc.perform(auth(get(DATASETS + "/" + datasetId), "owner-t10b",
                "provider")).andReturn();
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        assertThat(payload(mine).path("dataNo").asText()).startsWith("DS");
        // 非本人与不存在同形（均为 1007C0005）+ 越权探测 DENIED 留痕
        final MvcResult others = mockMvc.perform(auth(get(DATASETS + "/" + datasetId), "admin-t10b",
                "provider")).andReturn();
        final MvcResult absent = mockMvc.perform(auth(get(DATASETS + "/999999"), "admin-t10b",
                "provider")).andReturn();
        assertThat(others.getResponse().getStatus()).isEqualTo(404);
        assertThat(absent.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(others)).isEqualTo("1007C0005");
        assertThat(codeOf(absent)).isEqualTo("1007C0005");
        assertThat(root(others).path("message").asText())
                .isEqualTo(root(absent).path("message").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? "
                + "AND action = 'DENIED_READ' AND actor_subject_no = 'admin-t10b'", Integer.class, datasetId))
                .isEqualTo(1);
    }

    // ==== T15 并发与边界（评审循环 1 修复批：唯一索引兜底 / 分页边界 / 要素长度边界 / 数据标识口径）====

    @Test
    void concurrentRegistrationWindowIsGuardedByUniqueIndex() {
        // 绕过应用层同空间预检直插两次同空间同归一化名：唯一索引 uk_space_norm_name 兜底并发登记窗口
        // → 1007C0001（既不产生第二行、也不静默成功）
        final long spaceId = 1020L;
        // 共享容器重跑稳健性：本用例独占 space 1020，先清理本空间遗留行（避免 uk_data_no 冲突误红）
        jdbc.update("DELETE FROM dataset_action_log WHERE space_id = ?", spaceId);
        jdbc.update("DELETE FROM dataset WHERE space_id = ?", spaceId);
        final LocalDateTime now = LocalDateTime.now();
        final String conflictName = "并发窗口数据集";
        final DatasetActionLog log = new DatasetActionLog(null, "owner-t11c", spaceId, null, "REGISTER",
                null, DatasetStatus.ACTIVE.name(), ActionResult.SUCCESS, null, now);
        final String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        datasetRepository.create(
                dataset("DS" + datePart + "990001", spaceId, "owner-t11c", conflictName, now), log);
        assertThatThrownBy(() -> datasetRepository.create(
                dataset("DS" + datePart + "990002", spaceId, "owner-t11c", conflictName, now), log))
                .isInstanceOf(CatalogBizException.class)
                .hasMessage("同一空间已存在同名资源");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset WHERE space_id = ? "
                + "AND normalized_name = ?", Integer.class, spaceId, conflictName)).isEqualTo(1);
    }

    @Test
    void paginationBoundariesAndLongFieldValidation() throws Exception {
        // 空结果：该主体无资源 → total=0、list 空、分页字段回显默认值
        final MvcResult empty = mockMvc.perform(auth(get(DATASETS + "/mine"), "owner-t12", "provider"))
                .andReturn();
        assertThat(empty.getResponse().getStatus()).isEqualTo(200);
        assertThat(payload(empty).path("total").asLong()).isZero();
        assertThat(payload(empty).path("list")).isEmpty();
        assertThat(payload(empty).path("pageNum").asInt()).isEqualTo(1);
        assertThat(payload(empty).path("pageSize").asInt()).isEqualTo(10);
        // 分页边界（common-pagination 口径 ADR-005 §3.2）：pageSize 1~100、pageNum 从 1 起
        assertThat(minePage(1021L, "101", "1").getResponse().getStatus()).isEqualTo(400);
        assertThat(minePage(1021L, "10", "0").getResponse().getStatus()).isEqualTo(400);
        assertThat(minePage(1021L, "100", "1").getResponse().getStatus()).isEqualTo(200);
        // 要素长度边界（hifi §3.1 列宽）：简介 512 / 分类申报 64 放行；各超 1 字符 → 400
        assertThat(register("owner-t12", 1021L,
                longFieldBody("边界要素数据集", "简".repeat(512), "金".repeat(64))).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(register("owner-t12", 1021L,
                longFieldBody("超长简介数据集", "简".repeat(513), "金融")).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(register("owner-t12", 1021L,
                longFieldBody("超长分类数据集", "简介", "金".repeat(65))).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void dataNoDailySequenceResetsPerDayAndStopsAtUpperBound() throws Exception {
        final LocalDate today = LocalDate.now();
        // 当日重置口径（Q3-A）：前一日序号行置 5，不继承到当日
        jdbc.update("INSERT INTO dataset_no_seq (seq_date, seq_key, seq_value) VALUES (?, 'DATASET', 5) "
                + "ON DUPLICATE KEY UPDATE seq_value = 5", Date.valueOf(today.minusDays(1)));
        final int before = jdbc.query("SELECT seq_value FROM dataset_no_seq WHERE seq_date = ? "
                        + "AND seq_key = 'DATASET'", (rs, rowNum) -> rs.getInt(1), Date.valueOf(today))
                .stream().findFirst().orElse(0);
        final MvcResult registered = register("owner-t13", 1022L, registerBody("序号数据集", ""));
        assertThat(registered.getResponse().getStatus()).isEqualTo(200);
        assertThat(payload(registered).path("dataNo").asText())
                .isEqualTo("DS" + today.format(DateTimeFormatter.BASIC_ISO_DATE)
                        + String.format("%06d", before + 1));
        // 当日序号上限（6 位）：置 999999 后登记 → 超限按内部错误处理（不溢出编号），事务回滚无残留
        try {
            jdbc.update("UPDATE dataset_no_seq SET seq_value = 999999 WHERE seq_date = ? "
                    + "AND seq_key = 'DATASET'", Date.valueOf(today));
            final MvcResult overflow = register("owner-t13", 1022L, registerBody("序号上限数据集", ""));
            assertThat(overflow.getResponse().getStatus()).isEqualTo(500);
            assertThat(codeOf(overflow)).isEqualTo(ErrorCodes.INTERNAL_ERROR.value());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset WHERE space_id = 1022 "
                    + "AND normalized_name = '序号上限数据集'", Integer.class)).isZero();
        } finally {
            jdbc.update("UPDATE dataset_no_seq SET seq_value = ? WHERE seq_date = ? AND seq_key = 'DATASET'",
                    before + 1, Date.valueOf(today));
        }
    }

    // ==== 助手 ====

    private static MockHttpServletRequestBuilder auth(final MockHttpServletRequestBuilder builder,
            final String subject, final String roles) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles);
    }

    private MvcResult register(final String subject, final long spaceId, final String body) throws Exception {
        return mockMvc.perform(auth(post(SPACES + "/" + spaceId + "/datasets"), subject, "provider")
                .contentType(CONTENT_TYPE).content(body)).andReturn();
    }

    private long registerOk(final String subject, final long spaceId, final String name, final String extra)
            throws Exception {
        final MvcResult result = register(subject, spaceId, registerBody(name, extra));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return payload(result).path("id").asLong();
    }

    private MvcResult update(final String subject, final long datasetId, final String body) throws Exception {
        return mockMvc.perform(auth(put(DATASETS + "/" + datasetId), subject, "provider")
                .contentType(CONTENT_TYPE).content(body)).andReturn();
    }

    private MvcResult cancel(final String subject, final long datasetId, final String body) throws Exception {
        return mockMvc.perform(auth(post(DATASETS + "/" + datasetId + "/cancellation"), subject, "provider")
                .contentType(CONTENT_TYPE).content(body)).andReturn();
    }

    private MvcResult minePage(final long spaceId, final String pageSize, final String pageNum)
            throws Exception {
        return mockMvc.perform(auth(get(DATASETS + "/mine").param("spaceId", String.valueOf(spaceId))
                .param("pageSize", pageSize).param("pageNum", pageNum), "owner-t12", "provider"))
                .andReturn();
    }

    /** 要素长度边界用例专用请求体（可显式给足简介与分类申报，避免与 scaffold 字段重名）。 */
    private static String longFieldBody(final String name, final String intro, final String category) {
        return "{\"name\":\"" + name + "\",\"type\":\"DATASET\",\"intro\":\"" + intro + "\","
                + "\"tags\":[\"金融\"],\"declareCategory\":\"" + category + "\",\"declareLevel\":\"L2\"}";
    }

    /** 仓储级用例的领域对象构造（归一化名 = 名称，用例名不含空白无需归一化）。 */
    private static Dataset dataset(final String dataNo, final long spaceId, final String owner,
            final String name, final LocalDateTime now) {
        return new Dataset(null, dataNo, spaceId, owner, name, name, DatasetType.DATASET, "资源简介",
                "[\"金融\"]", "金融", DeclareLevel.L2, false, DatasetStatus.ACTIVE, now, now);
    }

    private static String registerBody(final String name, final String extra) {
        return "{\"name\":\"" + name + "\",\"type\":\"DATASET\",\"intro\":\"资源简介\","
                + "\"tags\":[\"金融\",\"普惠\"],\"declareCategory\":\"金融\",\"declareLevel\":\"L2\""
                + extra + "}";
    }

    /** 响应封套根节点（code/message/traceId/data）。 */
    private static JsonNode root(final MvcResult result) throws Exception {
        return MAPPER.readTree(body(result));
    }

    /** 响应 data 载荷节点（业务视图/分页对象）。 */
    private static JsonNode payload(final MvcResult result) throws Exception {
        return root(result).path("data");
    }

    private static String body(final MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String codeOf(final MvcResult result) throws Exception {
        return root(result).path("code").asText();
    }
}
