package com.ctds.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.space.domain.SubjectAdmission;
import com.ctds.space.domain.SubjectAdmissionPort;
import com.ctds.space.support.IsoSecondTimestamp;
import com.ctds.space.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 运营档治理查看留痕集成测试（DB-29，Q1~Q5 全 A，Testcontainers 实跑）：
 * ① 运营方成功访问 7 个治理读端点 → 各新增 GOVERNANCE_VIEW visit 留痕恰 1 行、四要素齐备
 *    （谁=实际登录主体编号/何时/对象=target_type+target_id/动作）且不含敏感原文（卡 §二 ①）；
 * ② 成员/所有者常规访问同一端点 → 不产生 visit 留痕（负向锚，Q4-A）；成员访问平台条目面 →
 *    403 拒绝且无 visit 行（拒绝路径语义不回归，卡 §二 ④）；
 * ③ 运营方访问已解散空间归档面（详情/留痕/有效策略——剧本 S3-6 场景）→ visit 留痕正常（卡 §二 ③）；
 * ④ 留痕分页自引用：本次查询结果不含本次写入的 visit 行，再次查询可见（读后写时序钉死，卡 §二）。
 * 主体资格端口 @MockitoBean（沿 3.2.3/3.2.4/3.2.5 先例）；本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SpaceGovernanceVisitLogIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/data-spaces";
    private static final String PLATFORM = "/api/v1/platform-policies";
    private static final String OPERATOR_ROLE = "platform.operator";
    private static final String OPERATOR = "operator-db29";
    private static final String VISIT = "GOVERNANCE_VIEW";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_space_governance_visit");
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

    // ==== 卡 §二 ①：运营方逐端点 visit 留痕（Q1-A 七端点全补）====

    @Test
    void operatorVisitEachGovernanceReadEndpointWritesOneAuditRow() throws Exception {
        final long id = enabledSpace("owner-db29", "治理查看留痕空间", "INVITE", "PRIVATE");
        addActiveMember(id, "member-db29");

        // ① 检索列表（平台面：target_type=SPACE、target_id/space_id 均空）
        final int listBefore = visitCount(null, OPERATOR);
        mockMvc.perform(auth(get(BASE), OPERATOR, OPERATOR_ROLE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        assertThat(visitCount(null, OPERATOR)).isEqualTo(listBefore + 1);
        assertLastVisitRow(null, "SPACE", null);

        // ② 空间详情
        final int detailBefore = visitCount(id, OPERATOR);
        mockMvc.perform(auth(get(BASE + "/" + id), OPERATOR, OPERATOR_ROLE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        assertThat(visitCount(id, OPERATOR)).isEqualTo(detailBefore + 1);
        assertLastVisitRow(id, "SPACE", id);

        // ③ 留痕分页（自引用：本次结果不含本次 visit 行——total=调用前行数；再次查询 total+1 可见——读后写钉死）
        final int logsBefore = visitCount(id, OPERATOR);
        final int allRowsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ?", Integer.class, id);
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), OPERATOR, OPERATOR_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(allRowsBefore));
        assertThat(visitCount(id, OPERATOR)).isEqualTo(logsBefore + 1);
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), OPERATOR, OPERATOR_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(allRowsBefore + 1));

        // ④ 有效策略
        final int policyBefore = visitCount(id, OPERATOR);
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), OPERATOR, OPERATOR_ROLE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        assertThat(visitCount(id, OPERATOR)).isEqualTo(policyBefore + 1);
        assertLastVisitRow(id, "SPACE", id);

        // ⑤ 成员列表
        final int memberBefore = visitCount(id, OPERATOR);
        mockMvc.perform(auth(get(BASE + "/" + id + "/members"), OPERATOR, OPERATOR_ROLE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        assertThat(visitCount(id, OPERATOR)).isEqualTo(memberBefore + 1);
        assertLastVisitRow(id, "SPACE", id);

        // ⑥ 准入单列表
        final int admissionBefore = visitCount(id, OPERATOR);
        mockMvc.perform(auth(get(BASE + "/" + id + "/admissions"), OPERATOR, OPERATOR_ROLE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        assertThat(visitCount(id, OPERATOR)).isEqualTo(admissionBefore + 1);
        assertLastVisitRow(id, "SPACE", id);

        // ⑦ 平台策略条目列表（平台面：space_id 空、target_type=POLICY、target_id 空）
        final int platformBefore = visitCount(null, OPERATOR);
        mockMvc.perform(auth(get(PLATFORM), OPERATOR, OPERATOR_ROLE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        assertThat(visitCount(null, OPERATOR)).isEqualTo(platformBefore + 1);
        assertLastVisitRow(null, "POLICY", null);
    }

    // ==== 卡 §二 ②：成员/所有者常规访问不写 visit（Q4-A 负向锚）+ 拒绝路径不回归 ====

    @Test
    void memberOrOwnerVisitDoesNotWriteAuditRowAndDenialPathStaysIntact() throws Exception {
        final long id = enabledSpace("owner-db29b", "成员访问不写留痕空间", "INVITE", "PRIVATE");
        addActiveMember(id, "member-db29b");

        // 成员常规访问（允许面）：成功但不写 visit 行
        final int before = visitCount(id, OPERATOR);
        mockMvc.perform(auth(get(BASE + "/" + id), "member-db29b", "user")).andExpect(status().isOk());
        mockMvc.perform(auth(get(BASE + "/" + id + "/members"), "member-db29b", "user")).andExpect(status().isOk());
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), "member-db29b", "user"))
                .andExpect(status().isOk());
        assertThat(visitCount(id, OPERATOR)).isEqualTo(before);

        // 所有者常规访问：同样不写 visit 行
        mockMvc.perform(auth(get(BASE + "/" + id), "owner-db29b", "user")).andExpect(status().isOk());
        assertThat(visitCount(id, OPERATOR)).isEqualTo(before);

        // 成员访问平台条目面：无 platform.policy 权限 → 403 拒绝且无 visit 行（拒绝语义不回归）
        mockMvc.perform(auth(get(PLATFORM), "member-db29b", "user")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        assertThat(visitCount(null, OPERATOR)).isEqualTo(before);

        // 非成员越权（非运营方）：对外拒绝形态不变 + 拒绝留痕照旧、visit 行为零混淆
        mockMvc.perform(auth(get(BASE + "/" + id), "outsider-db29", "user")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("1006C0004"));
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), "outsider-db29", "user"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("1006C0007"));
        assertThat(visitCount(id, OPERATOR)).isEqualTo(before);
        final Integer denied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ACCESS_DENIED' "
                        + "AND operator = 'outsider-db29' AND result = 'DENIED'", Integer.class, id);
        assertThat(denied).isEqualTo(2);
    }

    // ==== 卡 §二 ③：已解散空间归档面 visit 留痕正常（剧本 S3-6 场景）====

    @Test
    void operatorVisitsDissolvedSpaceArchivedFaceStillAudited() throws Exception {
        final long id = enabledSpace("owner-db29c", "解散归档留痕空间", "INVITE", "PRIVATE");
        mockMvc.perform(auth(post(BASE + "/" + id + "/dissolution"), "owner-db29c", "user")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"confirmDissolve\":true}"))
                .andExpect(status().isOk());

        final int before = visitCount(id, OPERATOR);
        mockMvc.perform(auth(get(BASE + "/" + id), OPERATOR, OPERATOR_ROLE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        mockMvc.perform(auth(get(BASE + "/" + id + "/action-logs"), OPERATOR, OPERATOR_ROLE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        mockMvc.perform(auth(get(BASE + "/" + id + "/policies/effective"), OPERATOR, OPERATOR_ROLE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        assertThat(visitCount(id, OPERATOR)).isEqualTo(before + 3);
        assertLastVisitRow(id, "SPACE", id);
    }

    // ==== 助手 ====

    private static MockHttpServletRequestBuilder auth(final MockHttpServletRequestBuilder builder,
            final String subject, final String roles) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles);
    }

    private long enabledSpace(final String owner, final String name, final String accessMode,
            final String visibility) throws Exception {
        final org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                        auth(post(BASE), owner, "user").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"" + name + "\",\"sceneType\":\"OTHER\",\"accessMode\":\""
                                        + accessMode + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0")).andReturn();
        final long id = MAPPER.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        mockMvc.perform(auth(post(BASE + "/" + id + "/enablement"), owner, "user")).andExpect(status().isOk());
        return id;
    }

    private void addActiveMember(final long spaceId, final String subjectNo) {
        jdbc.update("INSERT INTO space_member (space_id, subject_no, role, status, joined_at) "
                + "VALUES (?, ?, 'MEMBER', 'ACTIVE', NOW())", spaceId, subjectNo);
    }

    /** GOVERNANCE_VIEW visit 行计数（spaceId 传 null = 平台面行，space_id IS NULL）。 */
    private int visitCount(final Long spaceId, final String operator) {
        final Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE action = 'GOVERNANCE_VIEW' AND operator = ? "
                        + "AND ((? IS NULL AND space_id IS NULL) OR space_id = ?)",
                Integer.class, operator, spaceId, spaceId);
        return count == null ? 0 : count;
    }

    /** 最近一行 visit 留痕四要素逐字段断言（含"何时"ISO 秒级与不含敏感原文：from/to/reason 均空）。 */
    private void assertLastVisitRow(final Long spaceId, final String targetType, final Long targetId) {
        final Map<String, Object> row = jdbc.queryForMap(
                "SELECT space_id, target_type, target_id, operator, result, reason, from_value, to_value, "
                        + "DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s') AS created_iso "
                        + "FROM space_action_log WHERE action = 'GOVERNANCE_VIEW' AND operator = ? "
                        + "AND ((? IS NULL AND space_id IS NULL) OR space_id = ?) ORDER BY id DESC LIMIT 1",
                OPERATOR, spaceId, spaceId);
        assertThat(row.get("space_id")).isEqualTo(spaceId);
        assertThat(row.get("target_type")).isEqualTo(targetType);
        assertThat(row.get("target_id")).isEqualTo(targetId);
        assertThat(row.get("operator")).isEqualTo(OPERATOR);
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        assertThat(row.get("reason")).isNull();
        assertThat(row.get("from_value")).isNull();
        assertThat(row.get("to_value")).isNull();
        IsoSecondTimestamp.assertSecondPrecisionIso("created_at", String.valueOf(row.get("created_iso")));
    }
}
