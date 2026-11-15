package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.SharedMySqlContainer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * WBS-3.9.6 T1/T3（hifi §1）：RBAC 授予/收回写面真库行为测试 + V5 seed 专项断言。
 * 载体沿 DomainGovernanceIntegrationTest 范式（MockMvc + Testcontainers MySQL + jdbc 源真跑
 * Flyway V1~V5），契约 = 3.9.6 P5 真机复核逐项一致口径：幂等键三元组 {role, permission,
 * changeOrderNo}、变更号出参（幂等重发为空串）、留痕八列、缺失单号 400 零写入、重复收回
 * 1013C0003 零副作用。DB-129 自我授予现状（P9 实锤可成功）不进 CI（不固化缺陷行为）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class RbacGovernanceBehaviorIntegrationTest {

    private static final String GRANT_PATH = "/api/v1/rbac/permissions/grant";
    private static final String REVOKE_PATH = "/api/v1/rbac/permissions/revoke";
    private static final String ROLES_PATH = "/api/v1/rbac/roles";
    private static final String CHANGES_PATH = "/api/v1/rbac/changes";
    private static final String SECADMIN = "platform.secadmin";
    private static final String SYSADMIN = "platform.sysadmin";
    private static final String AUDADMIN = "platform.audadmin";
    private static final String SUBJECT = "S-W396-T1-SECADMIN";

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_rbac_it");
        registry.add("ctds.auth.rbac.source", () -> "jdbc");
        registry.add("ctds.auth.rbac.schema", () -> "ctds_evidence_rbac_it");
        registry.add("ctds.domain.id", () -> "ctds-demo-domain");
        registry.add("ctds.domain.name", () -> "演示部署域");
        registry.add("ctds.domain.platform-version", () -> "V1.0");
        registry.add("ctds.domain.schema-list", () ->
                "ctds_evidence_rbac_it,ctds_subject,ctds_space");
        registry.add("ctds.domain.service-list", () ->
                "evidence-service,subject-service,space-service,gateway-service");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** T1#1：授予成功 → 200 + 变更号出参；库表新行生效；留痕行八列齐备。 */
    @Test
    void grantWritesRowAndEightColumnChangeLog() throws Exception {
        MvcResult result = mockMvc.perform(post(GRANT_PATH)
                        .header("X-Ctds-Subject", SUBJECT)
                        .header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json")
                        .content("{\"role\":\"reviewer\",\"permission\":\"metering.read\","
                                + "\"changeOrderNo\":\"CHG-W396-T1-GRANT-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        String changeNo = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data");
        assertThat(changeNo).as("3.9.5 E-2 口径：首次授予回显变更号 RC+16hex")
                .startsWith("RC").hasSize(18);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT change_no, change_order_no, changed_by, role_code, permission_point, "
                        + "before_value, after_value FROM role_permission_change_log "
                        + "WHERE change_order_no = ?", "CHG-W396-T1-GRANT-01");
        assertThat(row.get("change_no")).isEqualTo(changeNo);
        assertThat(row.get("changed_by")).isEqualTo(SUBJECT);
        assertThat(row.get("role_code")).isEqualTo("reviewer");
        assertThat(row.get("permission_point")).isEqualTo("metering.read");
        assertThat(row.get("before_value")).isEqualTo("0");
        assertThat(row.get("after_value")).isEqualTo("1");

        Map<String, Object> mapping = jdbc.queryForMap(
                "SELECT enabled, version_no FROM role_permission "
                        + "WHERE role_code = 'reviewer' AND permission_point = 'metering.read'");
        assertThat(mapping.get("enabled")).isEqualTo(1);
    }

    /** T1#2：授予后收回 → 库表行禁用（读面消失）；留痕行 afterValue = 原值。 */
    @Test
    void revokeDisablesRowAndLogsOriginalValue() throws Exception {
        mockMvc.perform(post(GRANT_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json")
                        .content("{\"role\":\"reviewer\",\"permission\":\"sandbox.read\","
                                + "\"changeOrderNo\":\"CHG-W396-T1-GRANT-02\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        MvcResult result = mockMvc.perform(post(REVOKE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json")
                        .content("{\"role\":\"reviewer\",\"permission\":\"sandbox.read\","
                                + "\"changeOrderNo\":\"CHG-W396-T1-REVOKE-02\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        String changeNo = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data");
        assertThat(changeNo).startsWith("RC").hasSize(18);

        Map<String, Object> mapping = jdbc.queryForMap(
                "SELECT enabled, version_no FROM role_permission "
                        + "WHERE role_code = 'reviewer' AND permission_point = 'sandbox.read'");
        assertThat(mapping.get("enabled")).isEqualTo(0);
        assertThat(mapping.get("version_no")).isEqualTo(2);
        Map<String, Object> log = jdbc.queryForMap(
                "SELECT before_value, after_value FROM role_permission_change_log "
                        + "WHERE change_order_no = ?", "CHG-W396-T1-REVOKE-02");
        assertThat(log.get("before_value")).isEqualTo("1");
        assertThat(log.get("after_value")).isEqualTo("0");

        // 收回后权限消失（B1-AC2 双向的收回腿）：roles 读面不再含该点
        MvcResult roles = mockMvc.perform(get(ROLES_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN))
                .andExpect(status().isOk())
                .andReturn();
        List<String> reviewerPoints = com.jayway.jsonpath.JsonPath.read(
                roles.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data[?(@.role == 'reviewer')].permissions[*]");
        assertThat(reviewerPoints).as("收回后权限消失（B1-AC2 收回腿）")
                .doesNotContain("sandbox.read")
                .contains("subject.read", "subject.review");
    }

    /** T1#3：重复授予同三元组幂等（空变更号 + 库零新增）；重复收回同口径零副作用拒绝。 */
    @Test
    void repeatedGrantReplaysIdempotentlyAndRepeatedRevokeIsRejected() throws Exception {
        String body = "{\"role\":\"reviewer\",\"permission\":\"billing.read\","
                + "\"changeOrderNo\":\"CHG-W396-T1-GRANT-03\"}";
        mockMvc.perform(post(GRANT_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty());
        // 原样重发：幂等成功（不报错）+ 空变更号 + 零新增留痕
        mockMvc.perform(post(GRANT_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").value(""));
        Integer logCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission_change_log "
                        + "WHERE change_order_no = 'CHG-W396-T1-GRANT-03'", Integer.class);
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_code = 'reviewer' "
                        + "AND permission_point = 'billing.read'", Integer.class);
        assertThat(logCount).as("幂等键三元组：重发零新增留痕").isEqualTo(1);
        assertThat(rowCount).as("幂等重发零新增映射行").isEqualTo(1);
        // 重复收回（同三元组）：零副作用业务拒绝，不产生留痕
        mockMvc.perform(post(REVOKE_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1013C0003"));
        Integer logCountAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission_change_log "
                        + "WHERE change_order_no = 'CHG-W396-T1-GRANT-03'", Integer.class);
        assertThat(logCountAfter).isEqualTo(1);
    }

    /** T1#4 / §5 边界 1：变更单号缺失/空串 → 400 校验拒绝，库零写入。 */
    @Test
    void missingOrBlankChangeOrderNoRejectedWithoutAnyWrite() throws Exception {
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission_change_log", Integer.class);
        mockMvc.perform(post(GRANT_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json")
                        .content("{\"role\":\"reviewer\",\"permission\":\"metering.read\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1013C0001"));
        mockMvc.perform(post(GRANT_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SECADMIN)
                        .contentType("application/json")
                        .content("{\"role\":\"reviewer\",\"permission\":\"metering.read\","
                                + "\"changeOrderNo\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1013C0001"));
        Integer after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission_change_log", Integer.class);
        assertThat(after).as("校验拒绝零写入").isEqualTo(before);
    }

    /** T1#5：roles 清单可读（sysadmin），V3 seed 15 角色 + V5 两点可见。 */
    @Test
    void rolesListIsReadableWithSeedAndV5Points() throws Exception {
        MvcResult result = mockMvc.perform(get(ROLES_PATH)
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", SYSADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> roles = com.jayway.jsonpath.JsonPath.read(body, "$.data[*].role");
        assertThat(roles).as("V3 迁移后角色全量可读（实测 18：14 业务/服务身份 + 三员 + admin；"
                        + "hifi 文本 '15' 为 yml 既有登记历史口径，§9 勘误登记）").hasSize(18)
                .contains(SYSADMIN, SECADMIN, AUDADMIN, "reviewer", "applicant");
        List<String> sysadminPoints = com.jayway.jsonpath.JsonPath.read(
                body, "$.data[?(@.role == 'platform.sysadmin')].permissions[*]");
        assertThat(sysadminPoints).as("V5 seed 两点经 API 可见")
                .contains("platform.monitor.read", "platform.alert.handle");
    }

    /** T1#6：changes 留痕分页可读（audadmin），含 V5 seed 留痕行 + 分页结构齐备。 */
    @Test
    void changesPaginationIsReadableAndContainsV5SeedRows() throws Exception {
        MvcResult result = mockMvc.perform(get(CHANGES_PATH + "?pageNum=1&pageSize=100")
                        .header("X-Ctds-Subject", SUBJECT).header("X-Ctds-Roles", AUDADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).as("V5 seed 留痕行经分页 API 可读")
                .contains("CHG-WBS-3.9.4-MONITOR-READ")
                .contains("CHG-WBS-3.9.4-ALERT-HANDLE");
        List<Map<String, String>> list = com.jayway.jsonpath.JsonPath.read(body, "$.data.list[*]");
        assertThat(list).as("八列出参结构齐备").isNotEmpty();
        assertThat(list.get(0)).containsKeys("changeNo", "changeOrderNo", "changedBy",
                "changedAt", "roleCode", "permissionPoint", "beforeValue", "afterValue");
    }

    /** T3 库级：V5 真迁移后两点在库生效 + 留痕行落库（wbs-3.9.4 口径）。 */
    @Test
    void v5SeedGrantsMonitorPermissionsWithChangeLogRows() {
        Integer monitorRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_code = 'platform.sysadmin' "
                        + "AND permission_point = 'platform.monitor.read' AND enabled = 1",
                Integer.class);
        Integer alertRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_code = 'platform.sysadmin' "
                        + "AND permission_point = 'platform.alert.handle' AND enabled = 1",
                Integer.class);
        assertThat(monitorRows).isEqualTo(1);
        assertThat(alertRows).isEqualTo(1);
        Map<String, Object> monitorLog = jdbc.queryForMap(
                "SELECT change_no, changed_by, before_value, after_value "
                        + "FROM role_permission_change_log WHERE change_order_no = ?",
                "CHG-WBS-3.9.4-MONITOR-READ");
        assertThat(monitorLog.get("change_no")).isEqualTo("RCW394MONITOR01");
        assertThat(monitorLog.get("changed_by")).isEqualTo("wbs-3.9.4");
        assertThat(monitorLog.get("before_value")).isEqualTo("0");
        assertThat(monitorLog.get("after_value")).isEqualTo("1");
        Map<String, Object> alertLog = jdbc.queryForMap(
                "SELECT change_no, changed_by FROM role_permission_change_log "
                        + "WHERE change_order_no = ?", "CHG-WBS-3.9.4-ALERT-HANDLE");
        assertThat(alertLog.get("change_no")).isEqualTo("RCW394ALERT01");
    }

    /** T3 文件级：V5 seed 文件存在且授两点给 platform.sysadmin + 含留痕行（沿文本断言范式）。 */
    @Test
    void v5SeedFileDeclaresMonitorGrantsAndChangeLog() throws Exception {
        Path root = Path.of("").toAbsolutePath().getParent().getParent();
        Path migration = root.resolve(
                "services/evidence-service/src/main/resources/db/migration/V5__grant_monitor_permissions.sql");
        assertThat(Files.exists(migration)).as("V5 seed 文件在位").isTrue();
        String sql = Files.readString(migration);
        assertThat(sql).contains("platform.sysadmin")
                .contains("platform.monitor.read")
                .contains("platform.alert.handle")
                .contains("role_permission_change_log")
                .contains("RCW394MONITOR01")
                .contains("RCW394ALERT01");
    }
}
