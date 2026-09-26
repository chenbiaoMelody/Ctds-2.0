package com.ctds.space;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
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
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 空间生命周期全链集成测试（规格 C-2.1 行为 1/2/6 验收标准 + WBS-3.2.3 hifi §7 T1~T12，
 * ADR-010 容器化基座）：创建要素/资格防枚举/归一化判重/幂等/状态机全边/启用前提/解散三写/
 * 同名先后解散边界/双轨权限/配置变更白名单/读面可见性。资格端口 @MockitoBean（客户端三态
 * 由 SubjectAdmissionClientTest 覆盖）。本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SpaceLifecycleIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/data-spaces";

    private static Path auditDir;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_space_lifecycle");
    }

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-space");
    }

    @AfterAll
    static void deleteAuditDir() throws Exception {
        try (Stream<Path> paths = Files.walk(auditDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @DynamicPropertySource
    static void auditProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.audit.file-dir", () -> auditDir.toString());
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

    // ==== T1 创建正向（行为 1 规则 1/4/6）====

    @Test
    void createHappyPathWithOwnerRowAndFourElementLog() throws Exception {
        final long id = createSpace("owner-t1", "敏捷协作空间");
        final JsonNode detail = getDetail("owner-t1", id);
        assertThat(detail.path("status").asText()).isEqualTo("CREATED");
        assertThat(detail.path("ownerSubjectNo").asText()).isEqualTo("owner-t1");
        // 行为 1 规则 1：创建者自动成为所有者（owner 成员行落库）
        final Integer ownerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
                Integer.class, id);
        assertThat(ownerRows).isEqualTo(1);
        // 行为 1 规则 4：创建留痕四要素（谁/何时/对象/动作）+ 结果
        final Integer createLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'CREATE' "
                        + "AND result = 'SUCCESS' AND operator = 'owner-t1' AND target_type = 'SPACE' "
                        + "AND to_value = 'CREATED' AND created_at IS NOT NULL",
                Integer.class, id);
        assertThat(createLogs).isEqualTo(1);
    }

    // ==== T2 资格门槛防枚举（行为 1 规则 1）====

    @Test
    void admissionGateUnifiedMessageAndUnavailableNeverMasquerades() throws Exception {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.NOT_ADMITTED);
        mockMvc.perform(auth(post(BASE), "owner-t2", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"资格空间T2\",\"sceneType\":\"OTHER\",\"accessMode\":\"OPEN\","
                                + "\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0001"))
                .andExpect(jsonPath("$.message").value("主体未入驻或不存在，无法执行该操作"));
        // 服务不可用 = 1006S0001，不冒充资格拒绝（防枚举同形的另一面）
        given(admissionPort.check(any())).willReturn(SubjectAdmission.UNAVAILABLE);
        mockMvc.perform(auth(post(BASE), "owner-t2", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不可用T2\",\"sceneType\":\"OTHER\",\"accessMode\":\"OPEN\","
                                + "\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("1006S0001"))
                .andExpect(jsonPath("$.message").value("主体服务暂不可用，请稍后重试"));
    }

    // ==== T3 归一化判重（行为 1 规则 3）====

    @Test
    void normalizedDuplicateRejectedWithinOwnerAllowedAcrossOwners() throws Exception {
        // 判重拒绝路径沿 subject 先例经仓储直接造数（绕开幂等结果缓存——同键窗口内复用首结果见 T4）
        jdbc.update("INSERT INTO space (name, normalized_name, scene_type, access_mode, visibility, "
                + "owner_subject_no, status) VALUES ('普惠金融空间', '普惠金融空间', 'FINTECH', 'OPEN', "
                + "'PUBLIC', 'owner-t3a', 'CREATED')");
        // 全角空格（U+3000）首尾绕过尝试：归一化后同名 → 拒绝
        mockMvc.perform(auth(post(BASE), "owner-t3a", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\u3000普惠金融空间\u3000\",\"sceneType\":\"FINTECH\","
                                + "\"accessMode\":\"OPEN\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0003"))
                .andExpect(jsonPath("$.message").value("同一所有者已存在同名空间"));
        // 跨所有者同名活跃空间允许（唯一性 = "同一所有者"口径）
        final long otherOwner = createSpace("owner-t3b", "普惠金融空间");
        assertThat(otherOwner).isPositive();
    }

    // ==== T4 幂等（行为 1 规则 5）====

    @Test
    void idempotentReplayReturnsFirstResult() throws Exception {
        final long first = createSpace("owner-t4", "幂等验证空间");
        final MvcResult replay = mockMvc.perform(auth(post(BASE), "owner-t4", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"幂等验证空间\",\"sceneType\":\"OTHER\",\"accessMode\":\"OPEN\","
                                + "\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        assertThat(MAPPER.readTree(replay.getResponse().getContentAsString())
                .path("data").path("id").asLong()).isEqualTo(first);
        final Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space WHERE owner_subject_no = 'owner-t4' AND normalized_name = ?",
                Integer.class, "幂等验证空间");
        assertThat(rows).isEqualTo(1);
    }

    // ==== T5 要素校验（行为 1 规则 2）====

    @Test
    void missingElementsRejectedFieldByField() throws Exception {
        mockMvc.perform(auth(post(BASE), "owner-t5", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1006C0005"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("名称"),
                        org.hamcrest.Matchers.containsString("场景类型"),
                        org.hamcrest.Matchers.containsString("参与方范围"),
                        org.hamcrest.Matchers.containsString("可见性"))));
        final Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space WHERE owner_subject_no = 'owner-t5'", Integer.class);
        assertThat(rows).isZero();
    }

    // ==== T6 状态机全边（行为 2 规则 1）====

    @Test
    void stateMachineFullChainAndIllegalEdgesRejected() throws Exception {
        // 正向链：CREATED→ACTIVE→FROZEN→ACTIVE→DISSOLVED
        final long id = createSpace("owner-t6a", "状态机全边空间");
        lifecycle("owner-t6a", id, "enablement", "ACTIVE");
        lifecycle("owner-t6a", id, "freezing", "FROZEN");
        lifecycle("owner-t6a", id, "unfreezing", "ACTIVE");
        dissolve("owner-t6a", id, "自然结项", "DISSOLVED");
        // 非法边：CREATED→FROZEN 拒绝
        final long created = createSpace("owner-t6b", "非法边空间");
        mockMvc.perform(auth(post(BASE + "/" + created + "/freezing"), "owner-t6b", "user"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"));
        // 终态/重复动作：二次启用拒绝；解散后再动作拒绝
        final long twice = createSpace("owner-t6c", "终态空间");
        lifecycle("owner-t6c", twice, "enablement", "ACTIVE");
        mockMvc.perform(auth(post(BASE + "/" + twice + "/enablement"), "owner-t6c", "user"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"));
        dissolve("owner-t6c", twice, "结项", "DISSOLVED");
        mockMvc.perform(auth(post(BASE + "/" + twice + "/enablement"), "owner-t6c", "user"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"));
    }

    // ==== T7 启用前提（行为 2 规则 2）====

    @Test
    void enableRequiresOwnerStillAdmitted() throws Exception {
        final long id = createSpace("owner-t7", "启用资格空间");
        given(admissionPort.check(any())).willReturn(SubjectAdmission.NOT_ADMITTED);
        mockMvc.perform(auth(post(BASE + "/" + id + "/enablement"), "owner-t7", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0001"));
        assertThat(statusOf(id)).isEqualTo("CREATED");
    }

    // ==== T8 解散契约（行为 2 规则 4/6）====

    @Test
    void dissolveRequiresConfirmAndWritesThreeInOneTransaction() throws Exception {
        final long id = createSpace("owner-t8", "解散契约空间");
        lifecycle("owner-t8", id, "enablement", "ACTIVE");
        jdbc.update("INSERT INTO space_policy (scope, space_id, entry_key, entry_value, is_redline, status) "
                + "VALUES ('SPACE', ?, 'test.entry', 'v1', 0, 'ACTIVE')", id);
        // 二次确认缺失/非 true 一律 1006C0006
        mockMvc.perform(auth(post(BASE + "/" + id + "/dissolution"), "owner-t8", "user")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1006C0006"));
        mockMvc.perform(auth(post(BASE + "/" + id + "/dissolution"), "owner-t8", "user")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirmDissolve\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1006C0006"));
        // 显式 true：三写同事务
        dissolve("owner-t8", id, "项目结项", "DISSOLVED");
        final String lockOwner = jdbc.queryForObject(
                "SELECT space_id FROM space_name_lock WHERE normalized_name = '解散契约空间'", String.class);
        assertThat(lockOwner).isEqualTo(String.valueOf(id));
        assertThat(policyStatus(id)).isEqualTo("ARCHIVED");
        final Integer dissolveLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'DISSOLVE' "
                        + "AND from_value = 'ACTIVE' AND to_value = 'DISSOLVED' AND result = 'SUCCESS' "
                        + "AND reason = '项目结项' AND operator = 'owner-t8'",
                Integer.class, id);
        assertThat(dissolveLogs).isEqualTo(1);
    }

    // ==== T9 同名先后解散边界（Q6-A）====

    @Test
    void sequentialSameNameDissolutionsBothSucceedAndLockOnce() throws Exception {
        final long first = createSpace("owner-t9a", "同名先后解散空间");
        final long second = createSpace("owner-t9b", "同名先后解散空间");
        lifecycle("owner-t9a", first, "enablement", "ACTIVE");
        lifecycle("owner-t9b", second, "enablement", "ACTIVE");
        dissolve("owner-t9a", first, "先解散", "DISSOLVED");
        dissolve("owner-t9b", second, "后解散不因历史锁定被阻断", "DISSOLVED");
        final Integer lockRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_name_lock WHERE normalized_name = '同名先后解散空间'", Integer.class);
        assertThat(lockRows).isEqualTo(1);
        final String lockedBy = jdbc.queryForObject(
                "SELECT space_id FROM space_name_lock WHERE normalized_name = '同名先后解散空间'", String.class);
        assertThat(lockedBy).isEqualTo(String.valueOf(first));
    }

    // ==== T10 权限双轨（行为 2 规则 3/4/5）====

    @Test
    void dualTrackPermissions() throws Exception {
        final long id = createSpace("owner-t10", "权限双轨空间");
        lifecycle("owner-t10", id, "enablement", "ACTIVE");
        seedMember(id, "member-t10", "MEMBER");
        seedMember(id, "admin-t10", "ADMIN");
        // 空间内 MEMBER 冻结被拒 + DENIED 留痕
        mockMvc.perform(auth(post(BASE + "/" + id + "/freezing"), "member-t10", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        final Integer deniedLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'FREEZE' "
                        + "AND result = 'DENIED' AND operator = 'member-t10'",
                Integer.class, id);
        assertThat(deniedLogs).isEqualTo(1);
        // 非成员启用被拒
        mockMvc.perform(auth(post(BASE + "/" + id + "/enablement"), "outsider-t10", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        // ADMIN 解散被拒（仅所有者动作）
        mockMvc.perform(auth(post(BASE + "/" + id + "/dissolution"), "admin-t10", "user")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirmDissolve\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        // platform.operator（角色头档）全动作放行
        final long other = createSpace("owner-t10op", "运营方接管空间");
        lifecycle("platform-operator", "platform.operator", other, "enablement", "ACTIVE");
        lifecycle("platform-operator", "platform.operator", other, "freezing", "FROZEN");
        lifecycle("platform-operator", "platform.operator", other, "unfreezing", "ACTIVE");
        dissolve("platform-operator", "platform.operator", other, "平台治理解散", "DISSOLVED");
    }

    // ==== T11 配置变更（Q5-A + hifi §8 白名单语义）====

    @Test
    void configUpdateWhitelistAndAuditTrail() throws Exception {
        final long id = createSpace("owner-t11", "配置变更空间");
        lifecycle("owner-t11", id, "enablement", "ACTIVE");
        mockMvc.perform(auth(put(BASE + "/" + id), "owner-t11", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intro\":\"新简介\",\"effectiveFrom\":\"2026-01-01T00:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intro").value("新简介"));
        // 逐字段留痕"从何值→到何值"
        final Integer updateLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'UPDATE' "
                        + "AND result = 'SUCCESS' AND (from_value = 'intro:（未设置）' "
                        + "OR from_value = 'effectiveFrom:（未设置）')",
                Integer.class, id);
        assertThat(updateLogs).isEqualTo(2);
        // 白名单外字段 400（不可变更字段拒绝语义）
        mockMvc.perform(auth(put(BASE + "/" + id), "owner-t11", "user")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"改名尝试\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
        // 未启用空间不可变更
        final long created = createSpace("owner-t11b", "未启用配置空间");
        mockMvc.perform(auth(put(BASE + "/" + created), "owner-t11b", "user")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"intro\":\"提前改\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"));
    }

    // ==== T12 读面可见性（行为 6 规则 3 + hifi §8）====

    @Test
    void readFaceVisibilityFiltering() throws Exception {
        final long publicId = createSpaceWithVisibility("owner-t12a", "公开读面空间", "PUBLIC");
        final long privateId = createSpaceWithVisibility("owner-t12b", "私有读面空间", "PRIVATE");
        // 非成员列表：仅 PUBLIC；运营方全量
        final List<String> readerNames = listNames("reader-t12", "user", null);
        assertThat(readerNames).contains("公开读面空间").doesNotContain("私有读面空间");
        assertThat(listNames("platform-operator", "platform.operator", null))
                .contains("公开读面空间", "私有读面空间");
        // keyword 按归一化名称前缀检索
        assertThat(listNames("reader-t12", "user", "公开读面")).containsExactly("公开读面空间");
        // 非成员取不公开空间详情 = 不可见（1006C0004）
        mockMvc.perform(auth(get(BASE + "/" + privateId), "reader-t12", "user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("1006C0004"));
        // 所有者取全量详情（含成员构成）
        final JsonNode ownerView = getDetail("owner-t12b", privateId);
        assertThat(ownerView.path("members").isArray()).isTrue();
        assertThat(ownerView.path("members").size()).isEqualTo(1);
        // 非成员取公开空间详情 = 摘要（不含成员构成与所有者标识）
        final JsonNode summary = getDetail("reader-t12", publicId);
        assertThat(summary.has("members")).isFalse();
        assertThat(summary.has("ownerSubjectNo")).isFalse();
        assertThat(summary.path("status").asText()).isEqualTo("CREATED");
        // 解散后：详情可达（成员侧），列表不再出现（终态不出现在可检索面）
        dissolve("owner-t12a", publicId, "读面演示解散", "DISSOLVED");
        assertThat(listNames("reader-t12", "user", null)).doesNotContain("公开读面空间");
        final JsonNode dissolved = getDetail("owner-t12a", publicId);
        assertThat(dissolved.path("status").asText()).isEqualTo("DISSOLVED");
        // 未认证 401
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"));
    }

    // ==== 造数与断言助手 ====

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            final String subject, final String roles) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles);
    }

    /** 创建空间（PUBLIC/OPEN/OTHER 默认要素）并返回技术 id。 */
    private long createSpace(final String owner, final String name) throws Exception {
        return createSpaceWithVisibility(owner, name, "PUBLIC");
    }

    private long createSpaceWithVisibility(final String owner, final String name, final String visibility)
            throws Exception {
        final MvcResult result = mockMvc.perform(auth(post(BASE), owner, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"sceneType\":\"OTHER\",\"accessMode\":\"OPEN\","
                                + "\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    /** 执行生命周期动作并断言到达目标状态（默认 user 角色）。 */
    private void lifecycle(final String operator, final long id, final String action, final String expectedStatus)
            throws Exception {
        lifecycle(operator, "user", id, action, expectedStatus);
    }

    private void lifecycle(final String operator, final String roles, final long id, final String action,
            final String expectedStatus) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + id + "/" + action), operator, roles))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value(expectedStatus));
    }

    /** 解散（显式二次确认）并断言到达终态（默认 user 角色）。 */
    private void dissolve(final String operator, final long id, final String reason, final String expectedStatus)
            throws Exception {
        dissolve(operator, "user", id, reason, expectedStatus);
    }

    private void dissolve(final String operator, final String roles, final long id, final String reason,
            final String expectedStatus) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + id + "/dissolution"), operator, roles)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmDissolve\":true,\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value(expectedStatus));
    }

    /** 取详情响应 data 节点。 */
    private JsonNode getDetail(final String viewer, final long id) throws Exception {
        final MvcResult result = mockMvc.perform(auth(get(BASE + "/" + id), viewer, "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** 列表检索并返回名称清单。 */
    private List<String> listNames(final String viewer, final String roles, final String keyword)
            throws Exception {
        final MvcResult result = mockMvc.perform(auth(get(BASE), viewer, roles)
                        .param("pageNum", "1").param("pageSize", "50")
                        .param("keyword", keyword == null ? "" : keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        final JsonNode list = MAPPER.readTree(result.getResponse().getContentAsString())
                .path("data").path("list");
        return StreamSupport.stream(list.spliterator(), false)
                .map(node -> node.path("name").asText())
                .toList();
    }

    private void seedMember(final long spaceId, final String subjectNo, final String role) {
        jdbc.update("INSERT INTO space_member (space_id, subject_no, role, status) VALUES (?, ?, ?, 'ACTIVE')",
                spaceId, subjectNo, role);
    }

    private String statusOf(final long spaceId) {
        return jdbc.queryForObject("SELECT status FROM space WHERE id = ?", String.class, spaceId);
    }

    private String policyStatus(final long spaceId) {
        return jdbc.queryForObject(
                "SELECT status FROM space_policy WHERE space_id = ? AND entry_key = 'test.entry'",
                String.class, spaceId);
    }
}
