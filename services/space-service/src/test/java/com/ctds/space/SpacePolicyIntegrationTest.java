package com.ctds.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.space.domain.SubjectAdmission;
import com.ctds.space.domain.SubjectAdmissionPort;
import com.ctds.space.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
 * 空间策略继承与覆盖全链集成测试（规格 C-2.1~2.3 行为 7 验收标准 + WBS-3.2.5 hifi §8
 * T1~T13 及 T18/T19 评审修复批补充，Testcontainers 实跑）：继承默认/红线放宽拒（§6.5 代码强制 +
 * 拒绝留痕）/收紧成/冲突取严可解释/目录值域校验（含覆盖请求携带 redline 结构错配）/条目定位防探测/
 * 同值幂等/冻结与解散生命周期联动/平台面治理（端点 2 契约门、端点 3 列表分页）与权限矩阵/载体契约探针。
 * 平台基线种子由 V3 迁移就绪（三键：两红线一非红线）；主体资格端口 @MockitoBean（沿成员域先例）。
 * 本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SpacePolicyIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/data-spaces";
    private static final String PLATFORM_BASE = "/api/v1/platform-policies";
    private static final String OPERATOR = "platform.operator";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_space_policy");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SubjectAdmissionPort admissionPort;

    @BeforeEach
    void admitByDefault() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
    }

    /**
     * 平台基线每用例复位（平台级条目是共享库全局状态——T5/T11 的平台变更不得泄漏给其他用例；
     * 空间级数据以唯一主体编号隔离，无需复位）。
     */
    @BeforeEach
    void resetPlatformBaseline() {
        jdbc.update("DELETE FROM space_policy WHERE scope = 'PLATFORM'");
        jdbc.update("INSERT INTO space_policy (scope, space_id, platform_entry_id, entry_key, "
                + "entry_value, is_redline, status) VALUES ('PLATFORM', NULL, NULL, 'data.visibility', "
                + "'SPACE_MEMBER', 1, 'ACTIVE')");
        jdbc.update("INSERT INTO space_policy (scope, space_id, platform_entry_id, entry_key, "
                + "entry_value, is_redline, status) VALUES ('PLATFORM', NULL, NULL, 'data.retention', "
                + "'D90', 1, 'ACTIVE')");
        jdbc.update("INSERT INTO space_policy (scope, space_id, platform_entry_id, entry_key, "
                + "entry_value, is_redline, status) VALUES ('PLATFORM', NULL, NULL, 'member.data_export', "
                + "'ALLOWED', 0, 'ACTIVE')");
    }

    // ==== T1 继承默认（规则 1；剧本 S2-1；验收标准 1）====

    @Test
    void keyWithoutSpaceOverrideInheritsPlatformDefaultWithProvenance() throws Exception {
        final long id = enabledSpace("owner-t1", "策略继承空间T1", "INVITE", "PRIVATE");
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), "owner-t1", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].entryKey").value("data.visibility"))
                .andExpect(jsonPath("$.data[0].effectiveValue").value("SPACE_MEMBER"))
                .andExpect(jsonPath("$.data[0].source").value("PLATFORM"))
                .andExpect(jsonPath("$.data[0].provenance").value("INHERITED"))
                .andExpect(jsonPath("$.data[0].platformValue").value("SPACE_MEMBER"))
                .andExpect(jsonPath("$.data[0].redline").value(true))
                .andExpect(jsonPath("$.data[1].entryKey").value("data.retention"))
                .andExpect(jsonPath("$.data[1].effectiveValue").value("D90"))
                .andExpect(jsonPath("$.data[1].provenance").value("INHERITED"))
                .andExpect(jsonPath("$.data[2].entryKey").value("member.data_export"))
                .andExpect(jsonPath("$.data[2].effectiveValue").value("ALLOWED"))
                .andExpect(jsonPath("$.data[2].redline").value(false));
    }

    // ==== T2 红线放宽拒 + 拒绝留痕（规则 2；§6.5 硬约束；剧本 S2-2；验收标准 2）====

    @Test
    void redlineLooseningOverrideIsRejectedWithAuditLog() throws Exception {
        final long id = enabledSpace("owner-t2", "策略拒宽空间T2", "INVITE", "PRIVATE");
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t2", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"data.visibility\",\"entryValue\":\"ALL_PLATFORM\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0013"))
                .andExpect(jsonPath("$.message").value("该条目为平台级限制项，不可放宽"));
        // 拒绝同样留痕（规则 4）：from=当前生效值 → to=提交值，reason=业务文案
        final Integer rejectedLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'POLICY_OVERRIDE_REJECTED' "
                        + "AND result = 'DENIED' AND from_value = 'SPACE_MEMBER' AND to_value = 'ALL_PLATFORM' "
                        + "AND reason = '该条目为平台级限制项，不可放宽'",
                Integer.class, id);
        assertThat(rejectedLog).isEqualTo(1);
        // 有效策略保持平台级默认值
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), "owner-t2", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].effectiveValue").value("SPACE_MEMBER"))
                .andExpect(jsonPath("$.data[0].provenance").value("INHERITED"));
    }

    // ==== T3 红线收紧成 + from→to 留痕（规则 2；剧本 S2-3；验收标准 2）====

    @Test
    void redlineTighteningOverrideSucceedsAndLogsFromTo() throws Exception {
        final long id = enabledSpace("owner-t3", "策略收紧空间T3", "INVITE", "PRIVATE");
        final long platformEntryId = platformEntryId("data.retention");
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t3", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"data.retention\",\"entryValue\":\"D30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveValue").value("D30"))
                .andExpect(jsonPath("$.data.source").value("SPACE"))
                .andExpect(jsonPath("$.data.provenance").value("SPACE_EFFECTIVE"))
                .andExpect(jsonPath("$.data.platformValue").value("D90"))
                .andExpect(jsonPath("$.data.spaceValue").value("D30"));
        // 覆盖行落库：显式指向被覆盖平台条目（承接项①继承链）+ 留痕 from=D90 → to=D30
        final Integer overrideRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_policy WHERE space_id = ? AND entry_key = 'data.retention' "
                        + "AND scope = 'SPACE' AND status = 'ACTIVE' AND entry_value = 'D30' "
                        + "AND platform_entry_id = ? AND is_redline = 0",
                Integer.class, id, platformEntryId);
        assertThat(overrideRows).isEqualTo(1);
        final Integer overrideLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'POLICY_OVERRIDE' "
                        + "AND result = 'SUCCESS' AND from_value = 'D90' AND to_value = 'D30' "
                        + "AND target_type = 'POLICY' AND target_id = ?",
                Integer.class, id, platformEntryId);
        assertThat(overrideLog).isEqualTo(1);
    }

    // ==== T4 非红线覆盖生效（剧本 S2-4）====

    @Test
    void nonRedlineOverrideTakesEffectWithSpaceSource() throws Exception {
        final long id = enabledSpace("owner-t4", "策略覆盖空间T4", "INVITE", "PRIVATE");
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t4", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"APPROVAL_REQUIRED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveValue").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.data.source").value("SPACE"))
                .andExpect(jsonPath("$.data.provenance").value("SPACE_EFFECTIVE"));
    }

    // ==== T5 冲突取严可解释（规则 3；剧本 S2-5；验收标准 3）====

    @Test
    void conflictResolvesToStricterSideWithProvenance() throws Exception {
        final long id = enabledSpace("owner-t5", "策略取严空间T5", "INVITE", "PRIVATE");
        // 平台面收紧基线：export ALLOWED → FORBIDDEN（平台是红线的定义者，可放宽可收紧）
        final long exportEntryId = platformEntryId("member.data_export");
        mockMvc.perform(auth(put(PLATFORM_BASE + "/" + exportEntryId), "operator-t5", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryValue\":\"FORBIDDEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entryValue").value("FORBIDDEN"));
        // 空间覆盖放宽（非红线 → 允许落库），但取严兜底不生效：来源标注如实呈现
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t5", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"ALLOWED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveValue").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.source").value("PLATFORM"))
                .andExpect(jsonPath("$.data.provenance").value("SPACE_NOT_EFFECTIVE_TAKE_STRICTER"))
                .andExpect(jsonPath("$.data.spaceValue").value("ALLOWED"));
        final Integer overrideLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'POLICY_OVERRIDE' "
                        + "AND result = 'SUCCESS' AND from_value = 'FORBIDDEN' AND to_value = 'ALLOWED'",
                Integer.class, id);
        assertThat(overrideLog).isEqualTo(1);
    }

    // ==== T6 目录与值域校验（规则 6；Q5）====

    @Test
    void unknownKeyOrValueAndDuplicatePlatformEntryAreRejected() throws Exception {
        final long id = enabledSpace("owner-t6", "策略校验空间T6", "INVITE", "PRIVATE");
        // 键不在目录
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t6", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"bogus.key\",\"entryValue\":\"X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1006C0012"));
        // 值不在该键值域
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t6", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"data.retention\",\"entryValue\":\"D45\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1006C0012"));
        // 平台同键重复创建（应用前置判定，uk_scope_key DB 兜底）
        mockMvc.perform(auth(post(PLATFORM_BASE), "operator-t6", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"data.visibility\",\"entryValue\":\"ALL_PLATFORM\","
                                + "\"redline\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1006C0012"));
        // 覆盖请求携带 redline（结构错配——红线仅平台面可写，白名单外字段不静默忽略；hifi §8 T6/§9）
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t6", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"data.visibility\",\"entryValue\":\"ALL_PLATFORM\","
                                + "\"redline\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1006C0012"));
    }

    // ==== T7 条目定位防探测（Q5；覆盖目标缺失/平台变更端点定位失败统一 0014）====

    @Test
    void missingPlatformEntryUsesUnifiedNotFoundMessage() throws Exception {
        final long id = enabledSpace("owner-t7", "策略定位空间T7", "INVITE", "PRIVATE");
        // 数据准备：移除 retention 平台条目（断言后原值回插，不影响同库其他用例）
        jdbc.update("DELETE FROM space_policy WHERE scope = 'PLATFORM' AND entry_key = 'data.retention'");
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t7", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"data.retention\",\"entryValue\":\"D30\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("1006C0014"))
                .andExpect(jsonPath("$.message").value("策略条目不存在或已失效"));
        jdbc.update("INSERT INTO space_policy (scope, space_id, platform_entry_id, entry_key, entry_value, "
                + "is_redline, status) VALUES ('PLATFORM', NULL, NULL, 'data.retention', 'D90', 1, 'ACTIVE')");
        // 平台变更端点定位失败（条目 id 不存在）→ 0014 同形
        mockMvc.perform(auth(put(PLATFORM_BASE + "/99999"), "operator-t7", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryValue\":\"D30\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("1006C0014"));
    }

    // ==== T8 同值幂等（§7；缺口声明 2）====

    @Test
    void sameValueOverrideIsIdempotentWithoutNewRows() throws Exception {
        final long id = enabledSpace("owner-t8", "策略幂等空间T8", "INVITE", "PRIVATE");
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t8", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"FORBIDDEN\"}"))
                .andExpect(status().isOk());
        final Integer logCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'POLICY_OVERRIDE'",
                Integer.class, id);
        final Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_policy WHERE space_id = ? AND entry_key = 'member.data_export'",
                Integer.class, id);
        // 重复提交现值：200 无操作无留痕（沿 3.2.4 E7 先例）
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t8", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"FORBIDDEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveValue").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM space_action_log WHERE space_id = ? "
                + "AND action = 'POLICY_OVERRIDE'", Integer.class, id)).isEqualTo(logCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM space_policy WHERE space_id = ? "
                + "AND entry_key = 'member.data_export'", Integer.class, id)).isEqualTo(rowCount);
    }

    // ==== T9 冻结联动（规则 5；剧本 S3-2/S3-3；验收标准 4）====

    @Test
    void frozenSpaceRejectsPolicyWritesButRetainsConfiguredOverrides() throws Exception {
        final long id = enabledSpace("owner-t9", "策略冻结空间T9", "INVITE", "PRIVATE");
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t9", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"FORBIDDEN\"}"))
                .andExpect(status().isOk());
        freeze(id, "owner-t9");
        // 冻结期覆盖写拒（空间状态门槛 1006C0002——文案为 3.2.3 共用常量，hifi §2 勘误 E3）
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t9", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"ALLOWED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"))
                .andExpect(jsonPath("$.message").value("空间当前状态不允许该操作"));
        // 冻结期已配置覆盖保留（成员视图可读）
        final long memberId = activeMemberId(id, "member-t9");
        assertThat(memberId).isPositive();
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), "member-t9", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].spaceValue").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data[2].effectiveValue").value("FORBIDDEN"));
        // 恢复后覆盖仍生效（剧本 S3-3）
        unfreeze(id, "owner-t9");
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), "owner-t9", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].effectiveValue").value("FORBIDDEN"));
    }

    // ==== T10 解散归档不可变 + 可查门（规则 5；剧本 S3-4/S3-6；验收标准 4）====

    @Test
    void dissolvedSpacePolicyIsArchivedImmutableAndReadableByOwnerOnly() throws Exception {
        final long id = enabledSpace("owner-t10", "策略归档空间T10", "INVITE", "PRIVATE");
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t10", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"FORBIDDEN\"}"))
                .andExpect(status().isOk());
        final long memberId = activeMemberId(id, "member-t10");
        assertThat(memberId).isPositive();
        dissolve(id, "owner-t10");
        // 解散后策略写一律拒（归档不可变——3.2.3 解散三写已置 ARCHIVED + 本包写门；文案共用常量，勘误 E3）
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t10", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"ALLOWED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"))
                .andExpect(jsonPath("$.message").value("空间当前状态不允许该操作"));
        // 归档可查：owner 视图含归档值与 spaceStatus（规则 5 保留可查）
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), "owner-t10", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].spaceValue").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data[2].spaceStatus").value("ARCHIVED"))
                .andExpect(jsonPath("$.data[2].effectiveValue").value("FORBIDDEN"));
        // 普通成员不再可查（解散后不再对外提供访问）；平台运营管理员可查（S3-6）
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), "member-t10", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), "operator-t10", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].spaceStatus").value("ARCHIVED"));
    }

    // ==== T11 平台面治理：POLICY_DEFINE 留痕 + 权限门（规则 1/4；Q4）====

    @Test
    void platformGovernanceLogsDefineAndDeniesNonOperators() throws Exception {
        // 数据准备：移除 export 种子后经端点重建（登记 POLICY_DEFINE from=NULL → to=值）
        jdbc.update("DELETE FROM space_policy WHERE scope = 'PLATFORM' AND entry_key = 'member.data_export'");
        final MvcResult created = mockMvc.perform(auth(post(PLATFORM_BASE), "operator-t11", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"ALLOWED\","
                                + "\"redline\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entryKey").value("member.data_export"))
                .andReturn();
        final long entryId = MAPPER.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        final Integer createLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id IS NULL AND action = 'POLICY_DEFINE' "
                        + "AND result = 'SUCCESS' AND from_value IS NULL AND to_value = 'ALLOWED' "
                        + "AND target_type = 'POLICY' AND target_id = ?",
                Integer.class, entryId);
        assertThat(createLog).isEqualTo(1);
        // 值变更留痕 from→to
        mockMvc.perform(auth(put(PLATFORM_BASE + "/" + entryId), "operator-t11", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryValue\":\"FORBIDDEN\"}"))
                .andExpect(status().isOk());
        final Integer updateLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id IS NULL AND action = 'POLICY_DEFINE' "
                        + "AND result = 'SUCCESS' AND from_value = 'ALLOWED' AND to_value = 'FORBIDDEN' "
                        + "AND target_id = ?",
                Integer.class, entryId);
        assertThat(updateLog).isEqualTo(1);
        // 权限门：平台面仅 platform.policy 来源（角色头映射）——空间所有者（角色头 user）拒 + DENIED 留痕
        final long id = enabledSpace("owner-t11b", "平台门空间T11", "INVITE", "PRIVATE");
        mockMvc.perform(auth(post(PLATFORM_BASE), "owner-t11b", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"data.retention\",\"entryValue\":\"D30\",\"redline\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        final Integer deniedLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id IS NULL AND action = 'POLICY_DEFINE' "
                        + "AND result = 'DENIED' AND operator = 'owner-t11b'",
                Integer.class);
        assertThat(deniedLog).isEqualTo(1);
    }

    // ==== T12 权限矩阵（承接项③；行为 7 权限口径）====

    @Test
    void overrideAndReadPermissionMatrix() throws Exception {
        final long id = enabledSpace("owner-t12", "策略矩阵空间T12", "INVITE", "PRIVATE");
        final long memberId = activeMemberId(id, "member-t12");
        // member 覆盖 ✗（0007 + DENIED 留痕）
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "member-t12", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"FORBIDDEN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        final Integer deniedLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'POLICY_OVERRIDE' "
                        + "AND result = 'DENIED' AND operator = 'member-t12'",
                Integer.class, id);
        assertThat(deniedLog).isEqualTo(1);
        // 授予 admin 后覆盖 ✓
        grant(id, "owner-t12", memberId, "ADMIN");
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "member-t12", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"FORBIDDEN\"}"))
                .andExpect(status().isOk());
        // 视图读：成员 ✓ / 非成员 ✗（0007 + ACCESS_DENIED 留痕）
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), "member-t12", "user"))
                .andExpect(status().isOk());
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), "stranger-t12", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        final Integer accessDeniedLog = jdbc.queryForObject("SELECT COUNT(*) FROM space_action_log "
                + "WHERE space_id = ? AND action = 'ACCESS_DENIED' AND result = 'DENIED' "
                + "AND operator = 'stranger-t12' AND reason = '无权访问空间策略视图'",
                Integer.class, id);
        assertThat(accessDeniedLog).isEqualTo(1);
    }

    // ==== T13 载体契约探针（承接项①；3.2.2 §1.5）====

    @Test
    void ukScopeKeyGuardsSingleActiveOverridePerKeyAndRowPointsToPlatformEntry() throws Exception {
        final long id = enabledSpace("owner-t13", "载体探针空间T13", "INVITE", "PRIVATE");
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-t13", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"member.data_export\",\"entryValue\":\"FORBIDDEN\"}"))
                .andExpect(status().isOk());
        assertThat(platformEntryId("member.data_export")).isPositive();
        final Integer linkedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_policy WHERE space_id = ? AND entry_key = 'member.data_export' "
                        + "AND platform_entry_id = ?",
                Integer.class, id, platformEntryId("member.data_export"));
        assertThat(linkedRows).isEqualTo(1);
        // 反向探针：同空间同键第二条 ACTIVE 覆盖行直插必撞 uk_scope_key（DB 兜底实证）
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO space_policy (scope, space_id, platform_entry_id, entry_key, entry_value, "
                        + "is_redline, status) VALUES ('SPACE', ?, ?, 'member.data_export', 'ALLOWED', 0, 'ACTIVE')",
                id, platformEntryId("member.data_export")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ==== T18 平台端点 2 契约门（4 视角评审修复批；hifi §1/§4/§7：至少一项变更/同值幂等/
    // 红线标记变更同记 0/1）====

    @Test
    void platformEntryUpdateRejectsEmptyChangeIsIdempotentAndLogsRedlineFlip() throws Exception {
        final long entryId = platformEntryId("member.data_export");
        // 至少一项变更（entryValue/redline 均缺省）→ common PARAM_INVALID（hifi §1 端点 2 契约）
        mockMvc.perform(auth(put(PLATFORM_BASE + "/" + entryId), "operator-t18", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
        // 同值幂等（§7 双向的平台侧）：重复提交现值 → 200 无操作无留痕
        final long beforeLogs = defineLogCount(entryId);
        mockMvc.perform(auth(put(PLATFORM_BASE + "/" + entryId), "operator-t18", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryValue\":\"ALLOWED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entryValue").value("ALLOWED"));
        assertThat(defineLogCount(entryId)).isEqualTo(beforeLogs);
        // 红线标记变更（0→1）落库 + 留痕 reasonNote 同记 0/1（hifi §4）
        mockMvc.perform(auth(put(PLATFORM_BASE + "/" + entryId), "operator-t18", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redline\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.redline").value(true));
        final Integer flipLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id IS NULL AND action = 'POLICY_DEFINE' "
                        + "AND result = 'SUCCESS' AND from_value = 'ALLOWED' AND to_value = 'ALLOWED' "
                        + "AND reason = '红线标记由 0 变更为 1' AND target_id = ?",
                Integer.class, entryId);
        assertThat(flipLog).isEqualTo(1);
    }

    // ==== T19 平台条目列表（4 视角评审修复批；hifi §1 端点 3 治理面只读：分页基线 + 权限门；
    // 剧本 S1-5；交付物①）====

    @Test
    void platformEntryListReturnsPagedGovernanceViewAndGatesNonOperators() throws Exception {
        mockMvc.perform(auth(get(PLATFORM_BASE), "operator-t19", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.list.length()").value(3))
                .andExpect(jsonPath("$.data.list[0].entryKey").value("data.visibility"))
                .andExpect(jsonPath("$.data.list[0].entryValue").value("SPACE_MEMBER"))
                .andExpect(jsonPath("$.data.list[0].displayName").value("数据可见范围"))
                .andExpect(jsonPath("$.data.list[0].redline").value(true))
                .andExpect(jsonPath("$.data.list[0].status").value("ACTIVE"));
        // 分页（common-pagination）：pageSize=2 → 2 行 / total=3 / totalPages=2
        mockMvc.perform(auth(get(PLATFORM_BASE).param("pageNum", "1").param("pageSize", "2"),
                        "operator-t19", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        // 权限门：无 platform.policy 来源主体（角色头 user）拒——治理面只读不对外（0007 + DENIED 留痕）
        mockMvc.perform(auth(get(PLATFORM_BASE), "owner-t19", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
    }

    // ==== 场景 helper（唯一主体编号避撞唯一键，沿 SpaceMembershipIntegrationTest 先例）====

    private long createSpace(final String owner, final String name, final String accessMode,
            final String visibility) throws Exception {
        final MvcResult result = mockMvc.perform(auth(post(BASE), owner, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"sceneType\":\"OTHER\",\"accessMode\":\""
                                + accessMode + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private long enabledSpace(final String owner, final String name, final String accessMode,
            final String visibility) throws Exception {
        final long id = createSpace(owner, name, accessMode, visibility);
        mockMvc.perform(auth(post(BASE + "/" + id + "/enablement"), owner, "user"))
                .andExpect(status().isOk());
        return id;
    }

    private long platformEntryId(final String entryKey) {
        return jdbc.queryForObject("SELECT id FROM space_policy WHERE scope = 'PLATFORM' "
                + "AND entry_key = ? AND status = 'ACTIVE'", Long.class, entryKey);
    }

    private long defineLogCount(final long entryId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM space_action_log WHERE space_id IS NULL "
                + "AND action = 'POLICY_DEFINE' AND target_id = ?", Long.class, entryId);
    }

    private long activeMemberId(final long spaceId, final String subjectNo) {
        jdbc.update("INSERT INTO space_member (space_id, subject_no, role, status, joined_at) "
                + "VALUES (?, ?, 'MEMBER', 'ACTIVE', NOW())", spaceId, subjectNo);
        return jdbc.queryForObject("SELECT id FROM space_member WHERE space_id = ? AND subject_no = ? "
                + "AND status = 'ACTIVE'", Long.class, spaceId, subjectNo);
    }

    private void grant(final long spaceId, final String operator, final long memberId,
            final String role) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + spaceId + "/members/" + memberId + "/role-assignment"),
                        operator, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
    }

    private void dissolve(final long spaceId, final String operator) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + spaceId + "/dissolution"), operator, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmDissolve\":true}"))
                .andExpect(status().isOk());
    }

    private void freeze(final long spaceId, final String operator) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + spaceId + "/freezing"), operator, "user"))
                .andExpect(status().isOk());
    }

    private void unfreeze(final long spaceId, final String operator) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + spaceId + "/unfreezing"), operator, "user"))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder auth(final MockHttpServletRequestBuilder builder,
            final String subject, final String roles) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles);
    }
}
