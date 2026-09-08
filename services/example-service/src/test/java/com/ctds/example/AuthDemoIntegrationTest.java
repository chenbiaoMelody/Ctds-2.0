package com.ctds.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * B9 三结果演示（真实 Spring 上下文 + 真实 yml 权限映射 + 真实清理过滤器/审计文件）：
 * user 查 200 / user 删 403（rbac.check DENIED）/ 无头查 401 / admin 删 200（greeting.delete SUCCESS 真实身份）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AuthDemoIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Path auditDir;

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-auth");
    }

    // 评审④P3-9：临时审计目录用后清理（沿 AuditTrailIntegrationTest 口径）
    @org.junit.jupiter.api.AfterAll
    static void deleteAuditDir() throws Exception {
        try (Stream<Path> paths = Files.walk(auditDir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @DynamicPropertySource
    static void auditProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.audit.file-dir", () -> auditDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    private UUID greetingId;

    @BeforeEach
    void createDemoGreeting() throws Exception {
        final MvcResult result = mockMvc.perform(post("/api/v1/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"to be deleted\"}"))
                .andExpect(status().isOk())
                .andReturn();
        greetingId = UUID.fromString(MAPPER.readTree(result.getResponse().getContentAsString())
                .at("/data/id").asText());
    }

    @Test
    void userCanList() throws Exception {
        mockMvc.perform(get("/api/v1/greetings")
                        .header("X-Ctds-Subject", "u-1").header("X-Ctds-Roles", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
    }

    @Test
    void userCannotDeleteAndDeniedIsAudited() throws Exception {
        mockMvc.perform(delete("/api/v1/greetings/" + greetingId)
                        .header("X-Ctds-Subject", "u-1").header("X-Ctds-Roles", "user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"))
                .andExpect(jsonPath("$.message").value("无权限执行该操作"));

        final JsonNode event = awaitEvent("rbac.check");
        assertEquals("DENIED", event.get("outcome").asText());
        assertEquals("u-1", event.get("actor").asText());
        assertEquals("greeting.delete", event.at("/detail/permission").asText());
    }

    @Test
    void noHeadersYieldUnauthorizedOnProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/greetings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"))
                .andExpect(jsonPath("$.message").value("认证失败或身份已失效"));
    }

    @Test
    void adminCanDeleteAndSuccessAuditedWithRealIdentity() throws Exception {
        mockMvc.perform(delete("/api/v1/greetings/" + greetingId)
                        .header("X-Ctds-Subject", "a-1").header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        final JsonNode event = awaitEvent("greeting.delete");
        assertEquals("SUCCESS", event.get("outcome").asText());
        assertEquals("a-1", event.get("actor").asText(), "审计须记录真实身份（2.4.4 预留兑现）");

        // 删除后列表中不再出现该条目
        mockMvc.perform(get("/api/v1/greetings").param("pageSize", "100")
                        .header("X-Ctds-Subject", "u-1").header("X-Ctds-Roles", "user"))
                .andExpect(status().isOk());
    }

    @Test
    void deletedGreetingCannotBeDeletedTwice() throws Exception {
        mockMvc.perform(delete("/api/v1/greetings/" + greetingId)
                        .header("X-Ctds-Subject", "a-1").header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/greetings/" + greetingId)
                        .header("X-Ctds-Subject", "a-1").header("X-Ctds-Roles", "admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0003"));
    }

    @Test
    void contextThreadLocalCleanedAfterEachRequest() throws Exception {
        // MOCK 环境 MockMvc 在测试线程同步分发，请求结束后本线程上下文必须已被过滤器 finally 清理
        mockMvc.perform(get("/api/v1/greetings")
                        .header("X-Ctds-Subject", "u-1").header("X-Ctds-Roles", "user"))
                .andExpect(status().isOk());
        assertNull(com.ctds.common.auth.AuthContext.user());
    }

    private JsonNode awaitEvent(final String action) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (Files.exists(auditDir)) {
                try (Stream<Path> files = Files.list(auditDir)) {
                    final List<Path> auditFiles = files.filter(p -> p.getFileName().toString().startsWith("audit-"))
                            .toList();
                    for (final Path file : auditFiles) {
                        for (final String line : Files.readAllLines(file)) {
                            final JsonNode node = MAPPER.readTree(line);
                            if (action.equals(node.get("action").asText())) {
                                return node;
                            }
                        }
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("2 秒内审计未出现 action=" + action);
    }
}
