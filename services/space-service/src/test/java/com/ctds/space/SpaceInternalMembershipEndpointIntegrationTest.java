package com.ctds.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.space.support.SharedMySqlContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 服务间内部成员判定端点测试（WBS-3.3.2 hifi §1.2 Q2-A——本卡对既有空间的唯一行为变更；
 * 沿 subject InternalAdmissionController 先例的口径锚）：最小暴露两字段（spaceStatus + role|NONE）；
 * 空间不存在 → NONE/NONE 同形（防枚举）；功能门槛 = 服务身份专用权限点 space.internal.read
 * （仅 catalog-internal 持有——业务角色与 platform.operator 均不得访问，防空间状态与成员关系枚举）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SpaceInternalMembershipEndpointIntegrationTest {

    private static final String INTERNAL = "/api/v1/data-spaces/internal";

    private static Path auditDir;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_space_internal");
    }

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-space-internal");
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

    @Test
    void memberAndOwnerRolesExposedWithSpaceStatus() throws Exception {
        final long spaceId = insertSpace("内测空间一", "ACTIVE");
        insertMember(spaceId, "S-owner", "OWNER");
        insertMember(spaceId, "S-admin", "ADMIN");
        insertMember(spaceId, "S-member", "MEMBER");
        mockMvc.perform(internal(spaceId, "S-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.spaceStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.role").value("OWNER"));
        mockMvc.perform(internal(spaceId, "S-member"))
                .andExpect(jsonPath("$.data.role").value("MEMBER"));
        // 非成员 = NONE（与"空间不存在"同形但空间状态如实回 ACTIVE——两者语义分开）
        mockMvc.perform(internal(spaceId, "S-outsider"))
                .andExpect(jsonPath("$.data.spaceStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.role").value("NONE"));
    }

    @Test
    void terminalMemberRowCountsAsNone() throws Exception {
        final long spaceId = insertSpace("内测空间二", "ACTIVE");
        // 退出/移除行保留改终态：非活跃成员行 = NONE（防"已退出成员仍被判定为成员"）
        jdbc.update("INSERT INTO space_member (space_id, subject_no, role, status, joined_at, exited_at) "
                + "VALUES (?, 'S-left', 'MEMBER', 'LEFT', NOW(), NOW())", spaceId);
        mockMvc.perform(internal(spaceId, "S-left"))
                .andExpect(jsonPath("$.data.role").value("NONE"));
    }

    @Test
    void nonActiveSpaceStatusesExposedAsIs() throws Exception {
        for (final String status : new String[] {"CREATED", "FROZEN", "DISSOLVED"}) {
            final long spaceId = insertSpace("内测空间" + status, status);
            insertMember(spaceId, "S-owner2", "OWNER");
            mockMvc.perform(internal(spaceId, "S-owner2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.spaceStatus").value(status));
        }
    }

    @Test
    void unknownSpaceAnswersNoneNoneSameShape() throws Exception {
        mockMvc.perform(internal(999_999L, "S-owner3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.spaceStatus").value("NONE"))
                .andExpect(jsonPath("$.data.role").value("NONE"));
    }

    @Test
    void businessRolesAndOperatorCannotReadInternalSurface() throws Exception {
        final long spaceId = insertSpace("内测空间三", "ACTIVE");
        insertMember(spaceId, "S-owner4", "OWNER");
        // 业务角色（含 platform.operator 管理档）：不持有 space.internal.read → 403（防枚举收敛）
        mockMvc.perform(internalWithRoles(spaceId, "S-owner4", "member"))
                .andExpect(status().isForbidden());
        mockMvc.perform(internalWithRoles(spaceId, "S-owner4", "platform.operator"))
                .andExpect(status().isForbidden());
        // 无身份头 → 401（未认证）
        mockMvc.perform(get(INTERNAL + "/" + spaceId + "/memberships/S-owner4")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        // 服务身份 catalog-internal → 放行
        mockMvc.perform(internal(spaceId, "S-owner4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("OWNER"));
    }

    private MockHttpServletRequestBuilder internal(final long spaceId, final String subjectNo) {
        return internalWithRoles(spaceId, subjectNo, "catalog-internal");
    }

    private MockHttpServletRequestBuilder internalWithRoles(final long spaceId, final String subjectNo,
            final String roles) {
        return get(INTERNAL + "/" + spaceId + "/memberships/" + subjectNo)
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Ctds-Subject", "catalog-service")
                .header("X-Ctds-Roles", roles);
    }

    private long insertSpace(final String name, final String status) {
        jdbc.update("INSERT INTO space (name, normalized_name, scene_type, access_mode, visibility, "
                        + "owner_subject_no, status) VALUES (?, ?, 'OTHER', 'OPEN', 'PUBLIC', 'S-owner', ?)",
                name, name, status);
        return jdbc.queryForObject("SELECT id FROM space WHERE normalized_name = ?", Long.class, name);
    }

    private void insertMember(final long spaceId, final String subjectNo, final String role) {
        jdbc.update("INSERT INTO space_member (space_id, subject_no, role, status, joined_at) "
                + "VALUES (?, ?, ?, 'ACTIVE', NOW())", spaceId, subjectNo, role);
    }

    @Test
    void membersEndpointDoesNotExposeDetails() throws Exception {
        // 最小暴露：响应仅两字段（无空间名称/简介/成员列表）
        final long spaceId = insertSpace("内测空间四", "ACTIVE");
        insertMember(spaceId, "S-owner5", "OWNER");
        final String body = mockMvc.perform(internal(spaceId, "S-owner5"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("spaceStatus", "role").doesNotContain("内测空间四", "intro", "members");
    }
}
