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
import java.util.HashSet;
import java.util.Set;
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
 * 空间操作留痕只读端点集成测试（WBS-3.2.6 hifi §5 端点 25 + §7 T1~T4，Testcontainers 实跑）：
 * 权限矩阵（成员/运营方可读、非成员 403 + 拒绝留痕一行、解散后仅 owner 与运营方）、
 * 分页与排序（created_at 倒序 + id 次序稳定）、越界页码 1000C0001、出参字段白名单、
 * 空间不存在 1006C0004、留痕三类内容（CREATE / ACCESS_DENIED / 状态与配置变更含 from→to）。
 * 主体资格端口 @MockitoBean（沿 3.2.3/3.2.4/3.2.5 先例）；本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SpaceActionLogIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/data-spaces";
    private static final String OPERATOR = "platform.operator";
    /** 读面拒绝留痕理由（hifi §5：服务端常量 ACTION_LOG_VIEW_DENIED_LOG_REASON）。 */
    private static final String VIEW_DENIED_REASON = "无权访问空间操作留痕";
    /** 出参字段白名单（hifi §1 SpaceActionLogView，逐字段断言——多字段即红）。 */
    private static final Set<String> VIEW_FIELDS = Set.of("id", "action", "operator", "result", "reason",
            "fromValue", "toValue", "targetType", "targetId", "createdAt");

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_space_action_log");
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

    // ==== T1 权限矩阵：成员可读 / 非成员 403 + 拒绝留痕 1 行 / 运营方可读（行为 6 规则 1/2/5）====

    @Test
    void memberAndOperatorCanReadLogsWhileNonMemberIsDeniedWithOneAuditRow() throws Exception {
        final long id = enabledSpace("owner-t1a", "留痕权限空间T1A", "INVITE", "PRIVATE");
        addActiveMember(id, "member-t1a");

        // 成员可读（space.member）：CREATE + ENABLE 两行
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), "member-t1a", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(2));

        // 非成员：403 1006C0007 + 拒绝留痕恰好 1 行（对外拒绝、对内可审计）
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), "outsider-t1a", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        assertThat(deniedLogCount(id, "outsider-t1a")).isEqualTo(1);

        // 平台运营方：角色头映射 space.member → 可读（此时含上一步的拒绝留痕，共 CREATE + ENABLE + 拒绝 = 3 行）
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), "operator-t1a", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    void dissolvedSpaceLogsAreReadableByOwnerAndOperatorOnly() throws Exception {
        final long id = enabledSpace("owner-t1b", "留痕解散空间T1B", "INVITE", "PRIVATE");
        addActiveMember(id, "member-t1b");
        dissolve(id, "owner-t1b");

        // 解散后：仅 owner 或 platform.operator（与端点 24 同口径）
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), "owner-t1b", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), "operator-t1b", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        // 非 owner 成员：403 + 拒绝留痕 1 行
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), "member-t1b", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        assertThat(deniedLogCount(id, "member-t1b")).isEqualTo(1);
    }

    // ==== T2 分页与排序 + 越界页码 + 出参字段白名单（ADR-005 §3.2）====

    @Test
    void logsArePagedNewestFirstAndOutOfRangePageIsRejected() throws Exception {
        final long id = enabledSpace("owner-t2", "留痕分页空间T2", "OPEN", "PUBLIC");
        addActiveMember(id, "member-t2");

        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs").param("pageSize", "1"), "member-t2", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                // created_at DESC, id DESC：同秒并列时由 id 兜底，最新一行 = ENABLE
                .andExpect(jsonPath("$.data.list[0].action").value("ENABLE"));

        // 分页越界（pageNum 上限 10000）→ 1000C0001 由 PageQuery 承担
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs").param("pageNum", "10001"), "member-t2", "user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
    }

    @Test
    void logViewExposesOnlyWhitelistedFields() throws Exception {
        final long id = enabledSpace("owner-t2b", "留痕字段空间T2B", "OPEN", "PUBLIC");

        final MvcResult result = mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), "owner-t2b", "user"))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode firstRow = MAPPER.readTree(result.getResponse().getContentAsString())
                .path("data").path("list").get(0);
        final Set<String> fields = new HashSet<>();
        firstRow.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(VIEW_FIELDS);
    }

    // ==== T3 空间不存在 1006C0004 + 非成员响应与可见性无关（同形口径，行为 6 规则 1/3）====

    @Test
    void missingSpaceIsNotFoundAndNonMemberShapeIsUniformAcrossVisibility() throws Exception {
        mockMvc.perform(auth(get(BASE + "/999999999/action-logs"), "prober-t3", "user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("1006C0004"))
                .andExpect(jsonPath("$.message").value("空间不存在"));

        final long privateId = enabledSpace("owner-t3p", "留痕私有空间T3P", "INVITE", "PRIVATE");
        final long publicId = enabledSpace("owner-t3u", "留痕公开空间T3U", "OPEN", "PUBLIC");
        final MvcResult privateDenied = mockMvc.perform(
                        auth(get(BASE + "/" + privateId + "/action-logs"), "prober-t3", "user"))
                .andExpect(status().isForbidden())
                .andReturn();
        final MvcResult publicDenied = mockMvc.perform(
                        auth(get(BASE + "/" + publicId + "/action-logs"), "prober-t3", "user"))
                .andExpect(status().isForbidden())
                .andReturn();
        // 非同成员一律同一码同一文案——不因空间是否存在/是否公开而变形
        assertThat(codeOf(publicDenied)).isEqualTo(codeOf(privateDenied));
        assertThat(messageOf(publicDenied)).isEqualTo(messageOf(privateDenied));
    }

    // ==== T4 留痕内容三类：创建 / 越权拒绝 / 状态与配置变更（含 from→to）====

    @Test
    void logsCoverCreateDeniedAndStateChangeRows() throws Exception {
        final long id = enabledSpace("owner-t4", "留痕内容空间T4", "INVITE", "PRIVATE");
        mockMvc.perform(auth(put(BASE + "/" + id), "owner-t4", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intro\":\"留痕内容演示简介\"}"))
                .andExpect(status().isOk());
        // 越权读：非成员被拒 → 落 ACCESS_DENIED 留痕（拒绝同样留痕）
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), "prober-t4", "user"))
                .andExpect(status().isForbidden());

        final MvcResult result = mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), "owner-t4", "user")
                        .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode list = MAPPER.readTree(result.getResponse().getContentAsString()).path("data").path("list");

        // ① 创建留痕（四要素：动作 CREATE / 操作者 / 对象 SPACE / 结果 SUCCESS）
        final JsonNode create = rowOfAction(list, "CREATE");
        assertThat(create.path("operator").asText()).isEqualTo("owner-t4");
        assertThat(create.path("result").asText()).isEqualTo("SUCCESS");
        assertThat(create.path("targetType").asText()).isEqualTo("SPACE");
        assertThat(create.path("targetId").asLong()).isEqualTo(id);
        assertThat(create.path("toValue").asText()).isEqualTo("CREATED");
        assertThat(create.path("createdAt").asText()).isNotBlank();

        // ② 越权拒绝留痕（DENIED + 服务端常量理由）
        final JsonNode denied = rowOfAction(list, "ACCESS_DENIED");
        assertThat(denied.path("result").asText()).isEqualTo("DENIED");
        assertThat(denied.path("operator").asText()).isEqualTo("prober-t4");
        assertThat(denied.path("reason").asText()).isEqualTo(VIEW_DENIED_REASON);

        // ③ 状态变更留痕（从何值 → 到何值）+ 配置变更留痕
        final JsonNode enable = rowOfAction(list, "ENABLE");
        assertThat(enable.path("fromValue").asText()).isEqualTo("CREATED");
        assertThat(enable.path("toValue").asText()).isEqualTo("ACTIVE");
        final JsonNode update = rowOfAction(list, "UPDATE");
        assertThat(update.path("toValue").asText()).isEqualTo("intro:留痕内容演示简介");
    }

    // ==== 场景 helper（唯一主体编号与名称避撞唯一键，沿 SpacePolicyIntegrationTest 先例）====

    private long createSpace(final String owner, final String name, final String accessMode,
            final String visibility) throws Exception {
        final MvcResult result = mockMvc.perform(auth(post(BASE), owner, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"sceneType\":\"OTHER\",\"accessMode\":\""
                                + accessMode + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private long enabledSpace(final String owner, final String name, final String accessMode,
            final String visibility) throws Exception {
        final long id = createSpace(owner, name, accessMode, visibility);
        mockMvc.perform(auth(post(BASE + "/" + id + "/enablement"), owner, "user"))
                .andExpect(status().isOk());
        return id;
    }

    private void dissolve(final long spaceId, final String operator) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + spaceId + "/dissolution"), operator, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmDissolve\":true}"))
                .andExpect(status().isOk());
    }

    /** 直插活跃成员行（成员读面用例，沿 SpacePolicyIntegrationTest 先例）。 */
    private void addActiveMember(final long spaceId, final String subjectNo) {
        jdbc.update("INSERT INTO space_member (space_id, subject_no, role, status, joined_at) "
                + "VALUES (?, ?, 'MEMBER', 'ACTIVE', NOW())", spaceId, subjectNo);
    }

    private int deniedLogCount(final long spaceId, final String operator) {
        final Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ACCESS_DENIED' "
                        + "AND result = 'DENIED' AND operator = ? AND reason = ?",
                Integer.class, spaceId, operator, VIEW_DENIED_REASON);
        return count == null ? 0 : count;
    }

    private static JsonNode rowOfAction(final JsonNode list, final String action) {
        for (final JsonNode row : list) {
            if (action.equals(row.path("action").asText())) {
                return row;
            }
        }
        throw new AssertionError("留痕中缺少动作行：" + action);
    }

    private static String codeOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    private static String messageOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("message").asText();
    }

    private static MockHttpServletRequestBuilder auth(final MockHttpServletRequestBuilder builder,
            final String subject, final String roles) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles);
    }
}
