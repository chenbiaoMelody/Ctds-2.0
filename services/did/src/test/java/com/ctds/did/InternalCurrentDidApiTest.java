package com.ctds.did;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.did.support.SharedMySqlContainer;
import java.time.LocalDateTime;
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
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 内部只读端点（WBS-3.6.3 hifi §8-②，ADR-017 补记）：主体当前 DID 三态 + NONE + 权限口径。
 * 只读库表数据，不涉 KMS 签发。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class InternalCurrentDidApiTest {

    private static final String PATH = "/api/v1/did/internal/subjects/";
    private static final String SUBJECT = "S20261026000071";
    private static final String DID = "did:ctds:S20261026000071.1";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_did_connector_internal");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM did_identity");
        jdbc.update("DELETE FROM did_operation_log");
    }

    @Test
    void currentReturnsActiveDid() throws Exception {
        insert(SUBJECT, 1, DID, "ACTIVE", SUBJECT);
        mockMvc.perform(get(PATH + SUBJECT + "/current")
                        .header("X-Ctds-Subject", "connector-service")
                        .header("X-Ctds-Roles", "connector-internal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectNo").value(SUBJECT))
                .andExpect(jsonPath("$.data.did").value(DID))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void currentReturnsPendingIssue() throws Exception {
        insert(SUBJECT, 1, DID, "PENDING_ISSUE", SUBJECT);
        mockMvc.perform(get(PATH + SUBJECT + "/current")
                        .header("X-Ctds-Subject", "connector-service")
                        .header("X-Ctds-Roles", "connector-internal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_ISSUE"));
    }

    @Test
    void currentReturnsLatestRevoked() throws Exception {
        insert(SUBJECT, 1, DID, "REVOKED", null);
        mockMvc.perform(get(PATH + SUBJECT + "/current")
                        .header("X-Ctds-Subject", "connector-service")
                        .header("X-Ctds-Roles", "connector-internal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.did").value(DID))
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
    }

    @Test
    void currentReturnsNoneWhenNoRecord() throws Exception {
        mockMvc.perform(get(PATH + "S20261026000099" + "/current")
                        .header("X-Ctds-Subject", "connector-service")
                        .header("X-Ctds-Roles", "connector-internal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NONE"));
    }

    @Test
    void internalPointNotGrantedToAdminRole() throws Exception {
        insert(SUBJECT, 1, DID, "ACTIVE", SUBJECT);
        mockMvc.perform(get(PATH + SUBJECT + "/current")
                        .header("X-Ctds-Subject", "ops-admin")
                        .header("X-Ctds-Roles", "admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH + SUBJECT + "/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"));
    }

    private void insert(final String subjectNo, final int seq, final String did, final String status,
            final String guardKey) {
        final LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO did_identity (subject_no, issuance_seq, did, status, public_key_hex, "
                        + "key_ref, document_json, guard_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                subjectNo, seq, did, status, "04" + "ab".repeat(64), null, "{}", guardKey, now, now);
    }
}
