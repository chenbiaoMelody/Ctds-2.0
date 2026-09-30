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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 * 空间成员与权限全链集成测试（规格 C-2.1~2.3 行为 3/4/5/6-5 验收标准 + WBS-3.2.4 hifi §8 T1~T21，
 * ADR-010 容器化基座）：准入三形态与幂等/资格与状态门槛/逐动作权限矩阵/唯一所有者保护/退出移除/
 * 所有权转移四写/统一权限面收敛回归/读面拒绝留痕/移交④ from/to 一致性。
 * 资格端口 @MockitoBean（客户端三态由 SubjectAdmissionClientTest 覆盖）。
 * 本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SpaceMembershipIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/data-spaces";

    private static Path auditDir;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_space_membership");
    }

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-space-member");
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

    // ==== T1 邀请+确认生效 / 未确认不产生成员（行为 3 规则 2；剧本 S1-1/2）====

    @Test
    void invitationBecomesMembershipOnlyAfterConfirm() throws Exception {
        final long id = enabledSpace("owner-t1", "邀请制空间T1", "INVITE", "PRIVATE");
        final long admissionId = invite(id, "owner-t1", "invitee-t1", null);
        // 未确认：成员列表不含被邀方（未确认的邀请不产生成员关系）
        mockMvc.perform(auth(get(BASE + "/" + id + "/members"), "owner-t1", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[?(@.subjectNo == 'invitee-t1')]").isEmpty());
        // 确认：成为成员（成员数 +1）+ 留痕"邀请+确认"四要素
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/" + admissionId + "/confirmation"),
                        "invitee-t1", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.memberId").isNumber());
        final Integer memberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND status = 'ACTIVE'",
                Integer.class, id);
        assertThat(memberCount).isEqualTo(2);
        final Integer inviteLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ADMIT_INVITE' "
                        + "AND result = 'SUCCESS' AND operator = 'owner-t1' AND target_type = 'ADMISSION' "
                        + "AND target_id = ?",
                Integer.class, id, admissionId);
        assertThat(inviteLog).isEqualTo(1);
        final Integer confirmLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ADMIT_CONFIRM' "
                        + "AND result = 'SUCCESS' AND from_value = 'PENDING_CONFIRMATION' "
                        + "AND to_value = 'APPROVED'",
                Integer.class, id);
        assertThat(confirmLog).isEqualTo(1);
    }

    // ==== T2 重复准入幂等（行为 3 规则 4；Q3-A；剧本 S1-3）====

    @Test
    void repeatedAdmissionReturnsExistingWithoutNewRows() throws Exception {
        final long id = enabledSpace("owner-t2", "邀请制空间T2", "INVITE", "PRIVATE");
        final long admissionId = invite(id, "owner-t2", "member-t2", null);
        confirm(id, admissionId, "member-t2");
        // 已是成员：再邀请 → 返回既有成员关系，不新增行
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/invitations"), "owner-t2", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"member-t2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyMember").value(true))
                .andExpect(jsonPath("$.data.member.subjectNo").value("member-t2"));
        final Integer memberRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND subject_no = 'member-t2'",
                Integer.class, id);
        assertThat(memberRows).isEqualTo(1);
        // 待确认单存在：重复邀请他人 → 返回既有单（本测以第二主体验证待处理单幂等）
        final long firstId = invite(id, "owner-t2", "pending-t2", null);
        final long replayId = invite(id, "owner-t2", "pending-t2", null);
        assertThat(replayId).isEqualTo(firstId);
        final Integer pendingRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_admission WHERE space_id = ? AND subject_no = 'pending-t2'",
                Integer.class, id);
        assertThat(pendingRows).isEqualTo(1);
        // 第三态（Q3-A）：终态单（谢绝）后重新邀请 → 允许新单（规格只约束成员唯一，不设永久禁入）
        final long declinedId = invite(id, "owner-t2", "declined-t2", null);
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/" + declinedId + "/confirmation"),
                        "declined-t2", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DECLINE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"));
        final MvcResult reinvited = mockMvc.perform(
                        auth(post(BASE + "/" + id + "/admissions/invitations"), "owner-t2", "user")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectNo\":\"declined-t2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyMember").value(false))
                .andExpect(jsonPath("$.data.admission.status").value("PENDING_CONFIRMATION"))
                .andReturn();
        final long newAdmissionId = MAPPER.readTree(reinvited.getResponse().getContentAsString())
                .path("data").path("admission").path("id").asLong();
        assertThat(newAdmissionId).isNotEqualTo(declinedId);
        final Integer declinedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_admission WHERE space_id = ? AND subject_no = 'declined-t2'",
                Integer.class, id);
        assertThat(declinedRows).isEqualTo(2);
    }

    // ==== T3 资格门槛防枚举（行为 3 规则 1；剧本 S1-4）====

    @Test
    void notAdmittedInviteeAndApplicantGetUnifiedMessage() throws Exception {
        final long id = enabledSpace("owner-t3", "邀请制空间T3", "INVITE", "PRIVATE");
        final long openId = enabledSpace("owner-t3b", "公开空间T3", "OPEN", "PUBLIC");
        // 按参数匹配：ghost-t3 未入驻、其余主体（含操作者）正常——验证资格判定查的是被邀/申请主体本身
        given(admissionPort.check(any())).willAnswer(inv ->
                "ghost-t3".equals(inv.getArgument(0)) ? SubjectAdmission.NOT_ADMITTED : SubjectAdmission.ADMITTED);
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/invitations"), "owner-t3", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"ghost-t3\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0001"))
                .andExpect(jsonPath("$.message").value("主体未入驻或不存在，无法执行该操作"));
        mockMvc.perform(auth(post(BASE + "/" + openId + "/admissions/applications"), "ghost-t3", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0001"));
        final Integer admissionRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_admission WHERE space_id = ? OR space_id = ?",
                Integer.class, id, openId);
        assertThat(admissionRows).isZero();
        // 对照：已入驻主体资格判定通过（操作者=被邀方同主体场景不误伤）
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/invitations"), "owner-t3", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"owner-t3b\"}"))
                .andExpect(status().isOk());
    }

    // ==== T4 申请+审批（行为 3 规则 2/5；剧本 S1-6~9）====

    @Test
    void applicationStaysPendingUntilApprovedAndRejectionRecordsReason() throws Exception {
        final long id = enabledSpace("owner-t4", "公开空间T4", "OPEN", "PUBLIC");
        final MvcResult applyResult = mockMvc.perform(
                        auth(post(BASE + "/" + id + "/admissions/applications"), "applicant-t4", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyMember").value(false))
                .andExpect(jsonPath("$.data.admission.status").value("PENDING_APPROVAL"))
                .andReturn();
        final long admissionId = MAPPER.readTree(applyResult.getResponse().getContentAsString())
                .path("data").path("admission").path("id").asLong();
        // 未审批：不产生成员
        final Integer beforeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND subject_no = 'applicant-t4'",
                Integer.class, id);
        assertThat(beforeRows).isZero();
        // 批准 → 成员生效
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/" + admissionId + "/approval"),
                        "owner-t4", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        final Integer afterRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND subject_no = 'applicant-t4' "
                        + "AND status = 'ACTIVE'", Integer.class, id);
        assertThat(afterRows).isEqualTo(1);
        // 拒绝路径：第二主体申请被拒 → 留痕含理由且不产生成员
        final long secondAdmission = apply(id, "rejected-t4");
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/" + secondAdmission + "/approval"),
                        "owner-t4", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"场景不符暂不接纳\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        final Integer rejectLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ADMIT_REJECT' "
                        + "AND result = 'SUCCESS' AND reason = '场景不符暂不接纳'",
                Integer.class, id);
        assertThat(rejectLog).isEqualTo(1);
        final Integer rejectedMemberRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND subject_no = 'rejected-t4'",
                Integer.class, id);
        assertThat(rejectedMemberRows).isZero();
        // 申请留痕 target_id = 准入单主键（留痕"对象"四要素不降级——修复批回归断言）
        final Integer requestLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ADMIT_REQUEST' "
                        + "AND result = 'SUCCESS' AND target_type = 'ADMISSION' AND target_id = ?",
                Integer.class, id, admissionId);
        assertThat(requestLog).isEqualTo(1);
        // 审批拒绝缺理由 = 参数校验 400（与移除理由同通道，非准入单状态门槛 0010）
        final long noReasonAdmission = apply(id, "noreason-t4");
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/" + noReasonAdmission + "/approval"),
                        "owner-t4", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isBadRequest());
    }

    // ==== T5 空间状态门槛（行为 3 规则 3；剧本 S1-10~12）====

    @Test
    void nonActiveSpaceRejectsEveryAdmissionAction() throws Exception {
        final long id = enabledSpace("owner-t5", "公开空间T5", "OPEN", "PUBLIC");
        // 未启用空间（另建）拒绝申请
        final long createdId = createSpace("owner-t5b", "未启用空间T5", "OPEN", "PUBLIC");
        mockMvc.perform(auth(post(BASE + "/" + createdId + "/admissions/applications"), "applicant-t5", "user"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"));
        // 冻结后拒绝申请，恢复后放行（剧本 S1-10~12 环境复位）
        freeze(id, "owner-t5");
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/applications"), "applicant-t5", "user"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"));
        unfreeze(id, "owner-t5");
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/applications"), "applicant-t5", "user"))
                .andExpect(status().isOk());
        // 解散态（终态）：准入动作全拒（规格行为 3 规则 3"已解散一律拒绝"）
        final long memberId = activeMemberId(id, "member-t5");
        dissolve(id, "owner-t5");
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/applications"), "applicant-t5b", "user"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"));
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/invitations"), "owner-t5", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"outsider-t5\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"));
        // 解散后退出仍允许（行为 5 规则 4"终止成员关系不受阻"终态分支）
        mockMvc.perform(auth(post(BASE + "/" + id + "/leaving"), "member-t5", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LEFT"));
    }

    // ==== T6 角色授予正向 + 留痕 + 被授予者能力生效（行为 4 规则 2/6；剧本 S2-1）====

    @Test
    void roleGrantTakesEffectWithFourElementLogAndRevocable() throws Exception {
        final long id = enabledSpace("owner-t6", "邀请制空间T6", "INVITE", "PRIVATE");
        final long memberId = activeMemberId(id, "member-t6");
        // owner 授予 admin
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + memberId + "/role-assignment"),
                        "owner-t6", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
        final Integer grantLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ROLE_GRANT' "
                        + "AND result = 'SUCCESS' AND from_value = 'MEMBER' AND to_value = 'ADMIN' "
                        + "AND operator = 'owner-t6' AND target_type = 'MEMBER'",
                Integer.class, id);
        assertThat(grantLog).isEqualTo(1);
        // 被授予者随后具备管理动作能力（正向对照：邀请成功）
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/invitations"), "member-t6", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"newbie-t6\"}"))
                .andExpect(status().isOk());
        // 收回：admin 回 member
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + memberId + "/role-assignment"),
                        "owner-t6", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("MEMBER"));
        final Integer revokeLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ROLE_REVOKE' "
                        + "AND result = 'SUCCESS' AND from_value = 'ADMIN' AND to_value = 'MEMBER'",
                Integer.class, id);
        assertThat(revokeLog).isEqualTo(1);
        // 同角色重复设定 = 幂等无操作（200 返回现状态、无新增留痕——登记口径固化）
        grant(id, "owner-t6", memberId, "MEMBER");
        final Integer revokeLogAfterReplay = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ROLE_REVOKE' "
                        + "AND result = 'SUCCESS'", Integer.class, id);
        assertThat(revokeLogAfterReplay).isEqualTo(1);
    }

    // ==== T7 member 逐动作全拒 + DENIED 留痕逐动作齐备（行为 4 规则 3；剧本 S2-2）====

    @Test
    void memberIsDeniedEveryManagementActionWithDeniedLogs() throws Exception {
        final long id = enabledSpace("owner-t7", "公开空间T7", "OPEN", "PUBLIC");
        final long memberId = activeMemberId(id, "member-t7");
        final String[] actions = {"ADMIT_INVITE", "ADMIT_APPROVE", "ROLE_GRANT", "REMOVE", "FREEZE", "DISSOLVE"};
        // member 尝试邀请
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/invitations"), "member-t7", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"outsider-t7\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        // member 尝试审批（须有待处理申请——owner 先制造一个）
        final long applicantAdmission = apply(id, "applicant-t7");
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/" + applicantAdmission + "/approval"),
                        "member-t7", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        // member 尝试角色变更 / 移除
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + memberId + "/role-assignment"),
                        "member-t7", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + memberId + "/removal"),
                        "member-t7", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"测试\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        // member 尝试冻结 / 解散（生命周期管理动作同权限点）
        mockMvc.perform(auth(post(BASE + "/" + id + "/freezing"), "member-t7", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        mockMvc.perform(auth(post(BASE + "/" + id + "/dissolution"), "member-t7", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmDissolve\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        for (final String action : actions) {
            final Integer denied = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = ? "
                            + "AND result = 'DENIED' AND operator = 'member-t7'",
                    Integer.class, id, action);
            assertThat(denied).as("动作 %s 的 DENIED 留痕", action).isEqualTo(1);
        }
    }

    // ==== T8 仅所有者动作（行为 4 规则 3/5；剧本 S2-3）====

    @Test
    void adminCannotDissolveOrTransferOwnership() throws Exception {
        final long id = enabledSpace("owner-t8", "邀请制空间T8", "INVITE", "PRIVATE");
        final long adminId = activeMemberId(id, "admin-t8");
        grant(id, "owner-t8", adminId, "ADMIN");
        // admin 解散被拒（3.2.3 语义保持）
        mockMvc.perform(auth(post(BASE + "/" + id + "/dissolution"), "admin-t8", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmDissolve\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        // admin 发起所有权转移被拒（仅所有者动作，行为 4 规则 5）
        final long memberId = activeMemberId(id, "member-t8");
        mockMvc.perform(auth(post(BASE + "/" + id + "/ownership-transfer"), "admin-t8", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":" + memberId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        // admin 把他人改为 owner 亦拒（role-assignment 的 OWNER 目标角色门槛）
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + memberId + "/role-assignment"),
                        "admin-t8", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
    }

    // ==== T9 自我提权（行为 4 规则 5；剧本 S2-4）====

    @Test
    void memberCannotPromoteSelf() throws Exception {
        final long id = enabledSpace("owner-t9", "邀请制空间T9", "INVITE", "PRIVATE");
        final long memberId = activeMemberId(id, "member-t9");
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + memberId + "/role-assignment"),
                        "member-t9", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        final String role = jdbc.queryForObject(
                "SELECT role FROM space_member WHERE id = ?", String.class, memberId);
        assertThat(role).isEqualTo("MEMBER");
    }

    // ==== T10 非成员绕过 + ACCESS_DENIED 留痕（行为 4 规则 3 + 行为 6 规则 1；剧本 S2-5）====

    @Test
    void nonMemberDirectApiAccessIsDeniedServerSide() throws Exception {
        final long id = enabledSpace("owner-t10", "邀请制空间T10", "INVITE", "PRIVATE");
        mockMvc.perform(auth(get(BASE + "/" + id + "/members"), "outsider-t10", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        mockMvc.perform(auth(get(BASE + "/" + id + "/admissions"), "outsider-t10", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        // WBS-3.2.7 T2 断言强化：拒绝留痕"何时"要素补断（created_at 非空 + ISO 秒级可解析）
        assertDeniedLogsWithIsoSecondCreatedAt(id, "outsider-t10", null, 2);
    }

    // ==== T11 admin 正向邀请（行为 4 规则 2；剧本 S2-6——admin 能力未被误伤）====

    @Test
    void adminInvitationStillWorks() throws Exception {
        final long id = enabledSpace("owner-t11", "邀请制空间T11", "INVITE", "PRIVATE");
        final long adminId = activeMemberId(id, "admin-t11");
        grant(id, "owner-t11", adminId, "ADMIN");
        final long admissionId = invite(id, "admin-t11", "invitee-t11", null);
        confirm(id, admissionId, "invitee-t11");
        final Integer memberRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND subject_no = 'invitee-t11' "
                        + "AND status = 'ACTIVE'", Integer.class, id);
        assertThat(memberRows).isEqualTo(1);
    }

    // ==== T12 退出 + 立即失效（行为 5 规则 1/5；剧本 S3-1）====

    @Test
    void leavingRemovesMembershipAndRevokesAccessImmediately() throws Exception {
        final long id = enabledSpace("owner-t12", "邀请制空间T12", "INVITE", "PRIVATE");
        final long memberId = activeMemberId(id, "member-t12");
        mockMvc.perform(auth(post(BASE + "/" + id + "/leaving"), "member-t12", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LEFT"))
                .andExpect(jsonPath("$.data.exitedAt").isNotEmpty());
        // 成员列表不再含 D；其后续访问被拒（立即失效）
        mockMvc.perform(auth(get(BASE + "/" + id + "/members"), "member-t12", "user"))
                .andExpect(status().isForbidden());
        final Integer leaveLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'LEAVE' "
                        + "AND result = 'SUCCESS' AND from_value = 'MEMBER' AND to_value = 'LEFT'",
                Integer.class, id);
        assertThat(leaveLog).isEqualTo(1);
        final Integer activeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE id = ? AND status = 'ACTIVE'",
                Integer.class, memberId);
        assertThat(activeRows).isZero();
    }

    // ==== T13 移除含理由 + 立即失效（行为 5 规则 2/5；剧本 S3-2）====

    @Test
    void removalRecordsReasonAndRevokesAccessImmediately() throws Exception {
        final long id = enabledSpace("owner-t13", "邀请制空间T13", "INVITE", "PRIVATE");
        final long memberId = activeMemberId(id, "member-t13");
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + memberId + "/removal"),
                        "owner-t13", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"违反空间公约\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REMOVED"));
        final Integer removeLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'REMOVE' "
                        + "AND result = 'SUCCESS' AND reason = '违反空间公约' "
                        + "AND from_value = 'MEMBER' AND to_value = 'REMOVED'",
                Integer.class, id);
        assertThat(removeLog).isEqualTo(1);
        mockMvc.perform(auth(get(BASE + "/" + id + "/members"), "member-t13", "user"))
                .andExpect(status().isForbidden());
        // 移除须理由：缺理由被拒（通用参数校验通道）
        final long secondMember = activeMemberId(id, "member-t13b");
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + secondMember + "/removal"),
                        "owner-t13", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ==== T14 唯一所有者保护三路（行为 5 规则 1/2/3；剧本 S3-3）====

    @Test
    void ownerRowIsProtectedFromLeaveRemoveAndRoleChange() throws Exception {
        final long id = enabledSpace("owner-t14", "邀请制空间T14", "INVITE", "PRIVATE");
        final long ownerMemberId = activeMemberId(id, "owner-t14");
        // owner 退出被拒
        mockMvc.perform(auth(post(BASE + "/" + id + "/leaving"), "owner-t14", "user"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0009"));
        // owner 被移除被拒（即使 owner/admin 自己发起）
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + ownerMemberId + "/removal"),
                        "owner-t14", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"测试\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0009"));
        // owner 行角色变更被拒
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + ownerMemberId + "/role-assignment"),
                        "owner-t14", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0009"));
        // uk_active_owner 探针：直接插入第二个活跃 OWNER 行必被 DB 唯一索引拒绝
        final String insertSecondOwner = "INSERT INTO space_member (space_id, subject_no, role, status, "
                + "joined_at) VALUES (?, 'probe-t14', 'OWNER', 'ACTIVE', NOW())";
        org.springframework.dao.DataAccessException conflict = null;
        try {
            jdbc.update(insertSecondOwner, id);
        } catch (final org.springframework.dao.DataAccessException e) {
            conflict = e;
        }
        assertThat(conflict).as("uk_active_owner 兜底：第二活跃 OWNER 行必须被拒绝").isNotNull();
        // owner 三路拒绝统一落 DENIED 留痕（退出/移除/角色变更各 1 条——评审修复批口径对齐断言）
        final Integer leaveDenied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'LEAVE' "
                        + "AND result = 'DENIED' AND target_type = 'MEMBER' AND target_id = ?",
                Integer.class, id, ownerMemberId);
        assertThat(leaveDenied).isEqualTo(1);
        final Integer removeDenied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'REMOVE' "
                        + "AND result = 'DENIED' AND target_type = 'MEMBER' AND target_id = ?",
                Integer.class, id, ownerMemberId);
        assertThat(removeDenied).isEqualTo(1);
        final Integer roleDenied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ROLE_GRANT' "
                        + "AND result = 'DENIED' AND target_type = 'MEMBER' AND target_id = ?",
                Integer.class, id, ownerMemberId);
        assertThat(roleDenied).isEqualTo(1);
    }

    // ==== T15 冻结期边界（行为 5 规则 4 + Q6-A；剧本 S3-4/5）====

    @Test
    void frozenSpaceAllowsLeavingAndRemovalButBlocksAdmissionAndRoleGrant() throws Exception {
        final long id = enabledSpace("owner-t15", "邀请制空间T15", "INVITE", "PRIVATE");
        final long memberId = activeMemberId(id, "member-t15");
        final long adminId = activeMemberId(id, "admin-t15");
        grant(id, "owner-t15", adminId, "ADMIN");
        freeze(id, "owner-t15");
        // 冻结期：准入被拒
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/invitations"), "admin-t15", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"outsider-t15\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"));
        // 冻结期：授予角色被拒
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + memberId + "/role-assignment"),
                        "owner-t15", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0002"));
        // 冻结期：退出允许（终止成员关系不受阻）
        mockMvc.perform(auth(post(BASE + "/" + id + "/leaving"), "member-t15", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LEFT"));
        // 冻结期：移除允许（Q6-A 冻结只封"进"与"授权"）
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + adminId + "/removal"),
                        "owner-t15", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"冻结期治理\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REMOVED"));
        // 恢复：准入与授权恢复
        unfreeze(id, "owner-t15");
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/invitations"), "owner-t15", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"newbie-t15\"}"))
                .andExpect(status().isOk());
    }

    // ==== T16 所有权转移（行为 4 规则 5 + 行为 5 规则 1/3 + 移交②）====

    @Test
    void ownershipTransferSyncsColumnAndMemberRowsAtomically() throws Exception {
        final long id = enabledSpace("owner-t16", "邀请制空间T16", "INVITE", "PRIVATE");
        final long targetMemberId = activeMemberId(id, "successor-t16");
        mockMvc.perform(auth(post(BASE + "/" + id + "/ownership-transfer"), "owner-t16", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":" + targetMemberId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spaceOwnerSubjectNo").value("successor-t16"))
                .andExpect(jsonPath("$.data.formerOwner.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.newOwner.role").value("OWNER"));
        // 移交②：列口径与成员表双处同步
        final String ownerColumn = jdbc.queryForObject(
                "SELECT owner_subject_no FROM space WHERE id = ?", String.class, id);
        assertThat(ownerColumn).isEqualTo("successor-t16");
        final Integer activeOwnerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND role = 'OWNER' "
                        + "AND status = 'ACTIVE'", Integer.class, id);
        assertThat(activeOwnerRows).isEqualTo(1);
        // 留痕两行（ROLE_GRANT 新 owner + ROLE_REVOKE 原 owner 降 ADMIN）
        final Integer grantLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ROLE_GRANT' "
                        + "AND to_value = 'OWNER' AND reason LIKE '所有权转移%'",
                Integer.class, id);
        assertThat(grantLog).isEqualTo(1);
        final Integer revokeLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ROLE_REVOKE' "
                        + "AND from_value = 'OWNER' AND to_value = 'ADMIN'",
                Integer.class, id);
        assertThat(revokeLog).isEqualTo(1);
        // 能力切换：新 owner 有属主能力（可转移/可解散）；原 owner 不再是属主
        final long nextSuccessor = activeMemberId(id, "third-t16");
        mockMvc.perform(auth(post(BASE + "/" + id + "/ownership-transfer"), "successor-t16", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":" + nextSuccessor + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(auth(post(BASE + "/" + id + "/ownership-transfer"), "owner-t16", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":" + nextSuccessor + "}"))
                .andExpect(status().isForbidden());
        // 目标=本人（OWNER 行）→ 与"不存在/不活跃"统一 1006C0008 文案（防成员存在性探测）
        final long selfMemberId = activeMemberId(id, "third-t16");
        mockMvc.perform(auth(post(BASE + "/" + id + "/ownership-transfer"), "third-t16", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":" + selfMemberId + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("1006C0008"));
    }

    // ==== T17 权限面收敛回归（移交①；3.2.3 T10 语义不回归）====

    @Test
    void platformOperatorRetainsFullGovernanceAccessViaNewMapping() throws Exception {
        final long id = enabledSpace("owner-t17", "邀请制空间T17", "INVITE", "PRIVATE");
        // platform.operator 经新映射（space.admin,space.member）：邀请放行
        final long admissionId = inviteWithRoles(id, "operator-t17", "invitee-t17", null, "platform.operator");
        confirm(id, admissionId, "invitee-t17");
        // 审批面读：准入单列表放行
        mockMvc.perform(auth(get(BASE + "/" + id + "/admissions"), "operator-t17", "platform.operator"))
                .andExpect(status().isOk());
        // 成员列表（space.member）放行
        mockMvc.perform(auth(get(BASE + "/" + id + "/members"), "operator-t17", "platform.operator"))
                .andExpect(status().isOk());
        // 治理动作（冻结）放行（3.2.3 双轨语义保持）
        mockMvc.perform(auth(post(BASE + "/" + id + "/freezing"), "operator-t17", "platform.operator"))
                .andExpect(status().isOk());
        // 无角色头映射的普通 user 角色不被折算（未知角色 = 空集）
        mockMvc.perform(auth(post(BASE + "/" + id + "/unfreezing"), "outsider-t17", "user"))
                .andExpect(status().isForbidden());
    }

    // ==== T18 读面拒绝留痕（行为 6 规则 5 + 移交③；对外 404 同形不变、对内可审计）====

    @Test
    void nonMemberPrivateDetailKeeps404ShapeWithAccessDeniedLog() throws Exception {
        final long id = enabledSpace("owner-t18", "私密空间T18", "OPEN", "PRIVATE");
        mockMvc.perform(auth(get(BASE + "/" + id), "outsider-t18", "user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("1006C0004"));
        // WBS-3.2.7 T2 断言强化：拒绝留痕"何时"要素补断（保留 target_type='SPACE' 既有约束）
        assertDeniedLogsWithIsoSecondCreatedAt(id, "outsider-t18", "SPACE", 1);
        // 对照：公开空间详情对非成员返回摘要（可检索 ≠ 可访问资源，读面不留痕）
        final long publicId = enabledSpace("owner-t18b", "公开空间T18", "OPEN", "PUBLIC");
        mockMvc.perform(auth(get(BASE + "/" + publicId), "outsider-t18", "user"))
                .andExpect(status().isOk());
        final Integer publicDenied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ACCESS_DENIED'",
                Integer.class, publicId);
        assertThat(publicDenied).isZero();
    }

    // ==== WBS-3.2.7 T1 跨空间隔离字面用例（行为 6 规则 1）：他空间有效成员访问本空间被拒 ====

    @Test
    void crossSpaceMemberAccessingOtherSpaceIsDenied() throws Exception {
        final long spaceA = enabledSpace("owner-t24a", "跨空间隔离空间A", "INVITE", "PRIVATE");
        final long spaceB = enabledSpace("owner-t24b", "跨空间隔离空间B", "INVITE", "PRIVATE");
        final long memberIdA = activeMemberId(spaceA, "member-t24");
        assertThat(memberIdA).isPositive();
        // 正向对照：member-t24 在本方空间 A 读取正常（载体自证，排除"成员面整体失效"假红）
        mockMvc.perform(auth(get(BASE + "/" + spaceA + "/members"), "member-t24", "user"))
                .andExpect(status().isOk());
        // 字面场景：空间甲的有效成员直接访问空间乙的成员/准入单读端点 → 双拒（他空间成员身份不豁免隔离）
        mockMvc.perform(auth(get(BASE + "/" + spaceB + "/members"), "member-t24", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        mockMvc.perform(auth(get(BASE + "/" + spaceB + "/admissions"), "member-t24", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        // 每端点恰 1 条 ACCESS_DENIED 留痕（共 2 条）+ 四要素逐字段（谁/目标/结果/何时——
        // 两读端点拒绝留痕 target_type 恒 SPACE，库表无端点维度故按合计断言；评审① R1-2 采纳）
        assertDeniedLogsWithIsoSecondCreatedAt(spaceB, "member-t24", "SPACE", 2);
        // 拒绝留痕归属不混淆：空间甲不因本次跨空间请求产生任何拒绝留痕
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ACCESS_DENIED' "
                        + "AND operator = 'member-t24'", Integer.class, spaceA)).isZero();
    }

    // ==== T19 准入单状态机（行为 3 规则 2 + Q7 码值）====

    @Test
    void admissionStateMachineEnforcesEdgesAndModeMatching() throws Exception {
        final long id = enabledSpace("owner-t19", "邀请制空间T19", "INVITE", "PRIVATE");
        // 谢绝：DECLINED 且成员不产生
        final long admissionId = invite(id, "owner-t19", "decliner-t19", null);
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/" + admissionId + "/confirmation"),
                        "decliner-t19", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DECLINE\",\"reason\":\"暂不加入\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"));
        final Integer memberRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND subject_no = 'decliner-t19'",
                Integer.class, id);
        assertThat(memberRows).isZero();
        // 终态单再确认 → 1006C0010（显式前置门槛 + 乐观门槛双层）
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/" + admissionId + "/confirmation"),
                        "decliner-t19", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0010"));
        // 形态错配：向邀请制空间提交申请 → 1006C0011
        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/applications"), "applicant-t19", "user"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0011"));
        // 形态错配：向公开空间发邀请 → 1006C0011
        final long openId = enabledSpace("owner-t19b", "公开空间T19", "OPEN", "PUBLIC");
        // 重复审批：公开空间申请→批准后（APPROVED 终态）再发审批 → 1006C0010（approve 终态门槛触达）
        final long approvedId = apply(openId, "approver-t19");
        approve(openId, approvedId, "owner-t19b");
        mockMvc.perform(auth(post(BASE + "/" + openId + "/admissions/" + approvedId + "/approval"),
                        "owner-t19b", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"重复处理\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0010"));
        mockMvc.perform(auth(post(BASE + "/" + openId + "/admissions/invitations"), "owner-t19b", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"invitee-t19\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0011"));
    }

    // ==== T20 移交④ from/to 单一表达一致性锚 ====

    @Test
    void transitionLogsAlwaysMirrorGateParameters() throws Exception {
        final long id = enabledSpace("owner-t20", "公开空间T20", "OPEN", "PUBLIC");
        freeze(id, "owner-t20");
        unfreeze(id, "owner-t20");
        // 生命周期状态机留痕：from/to 与乐观门槛参数逐字一致（仓储统一回填——移交④收敛面）
        final Integer frozenLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'FREEZE' "
                        + "AND from_value = 'ACTIVE' AND to_value = 'FROZEN'",
                Integer.class, id);
        assertThat(frozenLog).isEqualTo(1);
        final Integer unfrozenLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'UNFREEZE' "
                        + "AND from_value = 'FROZEN' AND to_value = 'ACTIVE'",
                Integer.class, id);
        assertThat(unfrozenLog).isEqualTo(1);
        // 成员域角色变更留痕同口径（T6 细断言的汇总形态）
        final long memberId = activeMemberId(id, "member-t20");
        grant(id, "owner-t20", memberId, "ADMIN");
        final Integer roleLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE target_id = ? AND action = 'ROLE_GRANT' "
                        + "AND from_value = 'MEMBER' AND to_value = 'ADMIN'",
                Integer.class, memberId);
        assertThat(roleLog).isEqualTo(1);
    }

    // ==== T9b admin 目标=本人（自我提权显式门槛触达——删门槛此测试必红）====

    @Test
    void adminCannotPromoteSelf() throws Exception {
        final long id = enabledSpace("owner-t9b", "邀请制空间T9b", "INVITE", "PRIVATE");
        final long adminId = activeMemberId(id, "admin-t9b");
        grant(id, "owner-t9b", adminId, "ADMIN");
        // admin（有权者）对自己行角色变更 → 1006C0007（自我提权门槛在 canManage 之后仍须拦截）
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/" + adminId + "/role-assignment"),
                        "admin-t9b", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1006C0007"));
        final String role = jdbc.queryForObject(
                "SELECT role FROM space_member WHERE id = ?", String.class, adminId);
        assertThat(role).isEqualTo("ADMIN");
        final Integer deniedLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_action_log WHERE space_id = ? AND action = 'ROLE_GRANT' "
                        + "AND result = 'DENIED' AND operator = 'admin-t9b'",
                Integer.class, id);
        assertThat(deniedLogs).isEqualTo(1);
    }

    // ==== 端点 6 我的准入单（全链——URL 拼写与双方向过滤）====

    @Test
    void myAdmissionsListsBothDirectionsAcrossSpaces() throws Exception {
        final long inviteSpace = enabledSpace("owner-t6b", "邀请制空间T6b", "INVITE", "PRIVATE");
        final long openSpace = enabledSpace("owner-t6c", "公开空间T6c", "OPEN", "PUBLIC");
        // 收到的邀请（被邀方视角）
        final long invitationId = invite(inviteSpace, "owner-t6b", "me-t6d", null);
        // 发出的申请（申请人视角）
        final long applicationId = apply(openSpace, "me-t6d");
        mockMvc.perform(auth(get(BASE + "/admissions/mine"), "me-t6d", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[?(@.id == " + invitationId
                        + " && @.type == 'INVITATION' && @.status == 'PENDING_CONFIRMATION')]").isNotEmpty())
                .andExpect(jsonPath("$.data.list[?(@.id == " + applicationId
                        + " && @.type == 'APPLICATION' && @.status == 'PENDING_APPROVAL')]").isNotEmpty());
        // 只含本人单据（他人单据不出现）
        mockMvc.perform(auth(get(BASE + "/admissions/mine"), "owner-t6b", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[?(@.subjectNo == 'me-t6d')]").isEmpty());
    }

    // ==== 1006C0008 行为触发路径（目标定位失败三形态统一防探测文案）====

    @Test
    void memberRelationFailuresUseUnifiedNotFoundMessage() throws Exception {
        final long id = enabledSpace("owner-t8b", "邀请制空间T8b", "INVITE", "PRIVATE");
        // 角色变更目标行不存在 → 404 + 0008（不区分"不存在/已失效"）
        mockMvc.perform(auth(post(BASE + "/" + id + "/members/99999/role-assignment"), "owner-t8b", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("1006C0008"))
                .andExpect(jsonPath("$.message").value("成员关系不存在或已失效"));
        // 无活跃行主体退出 → 404 + 0008
        mockMvc.perform(auth(post(BASE + "/" + id + "/leaving"), "stranger-t8b", "user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("1006C0008"));
        // 转移目标 = 已退出（LEFT）成员行 → 404 + 0008（不活跃目标不暴露"曾存在"差异）
        final long memberToLeave = activeMemberId(id, "goner-t8b");
        mockMvc.perform(auth(post(BASE + "/" + id + "/leaving"), "goner-t8b", "user"))
                .andExpect(status().isOk());
        mockMvc.perform(auth(post(BASE + "/" + id + "/ownership-transfer"), "owner-t8b", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":" + memberToLeave + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("1006C0008"));
    }

    // ==== 端点 5 分页与 status 筛选（非法值 400——评审③S2-3）====

    @Test
    void admissionListSupportsStatusFilterAndPagination() throws Exception {
        final long id = enabledSpace("owner-t5b", "公开空间T5b", "OPEN", "PUBLIC");
        apply(id, "filter-t5b-a");
        apply(id, "filter-t5b-b");
        mockMvc.perform(auth(get(BASE + "/" + id + "/admissions"), "owner-t5b", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
        // status 筛选命中
        mockMvc.perform(auth(get(BASE + "/" + id + "/admissions"), "owner-t5b", "user")
                        .param("status", "PENDING_APPROVAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[?(@.status == 'PENDING_APPROVAL')]").isNotEmpty());
        // 分页参数生效
        mockMvc.perform(auth(get(BASE + "/" + id + "/admissions"), "owner-t5b", "user")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        // 非法 status → 400（通用参数通道，非 500）
        mockMvc.perform(auth(get(BASE + "/" + id + "/admissions"), "owner-t5b", "user")
                        .param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
    }

    // ==== 场景 helper（唯一主体编号避撞唯一键，沿 SpaceLifecycleIntegrationTest 先例）====

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

    private long invite(final long spaceId, final String operator, final String invitee,
            final String reason) throws Exception {
        return inviteWithRoles(spaceId, operator, invitee, reason, "user");
    }

    private long inviteWithRoles(final long spaceId, final String operator, final String invitee,
            final String reason, final String roles) throws Exception {
        final String body = reason == null ? "{\"subjectNo\":\"" + invitee + "\"}"
                : "{\"subjectNo\":\"" + invitee + "\",\"reason\":\"" + reason + "\"}";
        final MvcResult result = mockMvc.perform(
                        auth(post(BASE + "/" + spaceId + "/admissions/invitations"), operator, roles)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyMember").value(false))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .path("data").path("admission").path("id").asLong();
    }

    private long apply(final long spaceId, final String applicant) throws Exception {
        final MvcResult result = mockMvc.perform(
                        auth(post(BASE + "/" + spaceId + "/admissions/applications"), applicant, "user"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .path("data").path("admission").path("id").asLong();
    }

    private void confirm(final long spaceId, final long admissionId, final String invitee) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + spaceId + "/admissions/" + admissionId + "/confirmation"),
                        invitee, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM\"}"))
                .andExpect(status().isOk());
    }

    private void grant(final long spaceId, final String operator, final long memberId,
            final String role) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + spaceId + "/members/" + memberId + "/role-assignment"),
                        operator, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
    }

    private void approve(final long spaceId, final long admissionId, final String operator) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + spaceId + "/admissions/" + admissionId + "/approval"),
                        operator, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
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

    /**
     * 空间成员行 id（Q5-A 行级定位的测试侧入口）：已有活跃行（如 owner 创建即 OWNER 行）直接回查；
     * 否则测试侧直插 MEMBER 行作数据准备（被测行为是角色/移除/转移 API，不依赖准入 API 的形态匹配）。
     */
    private long activeMemberId(final long spaceId, final String subjectNo) {
        final Integer existingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND subject_no = ? "
                        + "AND status = 'ACTIVE'", Integer.class, spaceId, subjectNo);
        if (existingCount != null && existingCount > 0) {
            return jdbc.queryForObject("SELECT id FROM space_member WHERE space_id = ? AND subject_no = ? "
                    + "AND status = 'ACTIVE'", Long.class, spaceId, subjectNo);
        }
        jdbc.update("INSERT INTO space_member (space_id, subject_no, role, status, joined_at) "
                + "VALUES (?, ?, 'MEMBER', 'ACTIVE', NOW())", spaceId, subjectNo);
        return jdbc.queryForObject("SELECT id FROM space_member WHERE space_id = ? AND subject_no = ? "
                + "AND status = 'ACTIVE'", Long.class, spaceId, subjectNo);
    }

    private static MockHttpServletRequestBuilder auth(final MockHttpServletRequestBuilder builder,
            final String subject, final String roles) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles);
    }

    /**
     * 拒绝留痕断言（WBS-3.2.7 T1/T2）：条数精确 + 四要素"何时"补断——created_at 非空且 ISO 秒级可解析。
     * targetType 传 null 不过滤（保留各既有用例原约束面，仅增不弱化）。
     */
    private void assertDeniedLogsWithIsoSecondCreatedAt(final long spaceId, final String operator,
            final String targetType, final int expectedCount) {
        final StringBuilder sql = new StringBuilder(
                "SELECT operator, target_type, result, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s') AS created_iso "
                        + "FROM space_action_log WHERE space_id = ? AND action = 'ACCESS_DENIED' "
                        + "AND result = 'DENIED' AND operator = ? ");
        final List<Object> params = new ArrayList<>(List.of(spaceId, operator));
        if (targetType != null) {
            sql.append("AND target_type = ? ");
            params.add(targetType);
        }
        final List<Map<String, Object>> logs = jdbc.queryForList(sql.toString(), params.toArray());
        assertThat(logs).hasSize(expectedCount);
        for (final Map<String, Object> log : logs) {
            assertThat(log.get("operator")).isEqualTo(operator);
            if (targetType != null) {
                assertThat(log.get("target_type")).as("target_type 逐行一致").isEqualTo(targetType);
            }
            IsoSecondTimestamp.assertSecondPrecisionIso("created_at", String.valueOf(log.get("created_iso")));
        }
    }
}
