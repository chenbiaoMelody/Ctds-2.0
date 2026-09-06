package com.ctds.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * B7 端到端接入：创建成功记 SUCCESS 审计事件；非法排序被拒记 DENIED 事件且 JSON 日志含 errorCode；
 * 控制台日志为结构化 JSON（ADR-005 §3 第 6 项）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AuditTrailIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PARAM_INVALID_CODE = "1000C0001";
    private static final String PLAIN_LOG_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}.*\\s(INFO|WARN|ERROR)\\s.*";

    private static Path auditDir;

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-it");
    }

    @DynamicPropertySource
    static void auditProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.audit.file-dir", () -> auditDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createShouldRecordSuccessAuditEventToDailyFile() throws Exception {
        final MvcResult result = mockMvc.perform(post("/api/v1/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "t-audit-1")
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        final String createdId = MAPPER.readTree(result.getResponse().getContentAsString())
                .at("/data/id").asText();

        final Path file = awaitAuditFile();
        final JsonNode json = readEvent(file, "greeting.create");
        assertEquals("SUCCESS", json.get("outcome").asText());
        assertEquals("greeting", json.get("targetType").asText());
        assertEquals(createdId, json.get("targetId").asText());
        assertEquals("anonymous", json.get("actor").asText());
        assertEquals("example-service", json.get("service").asText());
        assertEquals("t-audit-1", json.get("traceId").asText());
    }

    @Test
    void rejectedListShouldRecordDeniedEventAndJsonLogWithErrorCode(final CapturedOutput output)
            throws Exception {
        mockMvc.perform(get("/api/v1/greetings").param("orderBy", "secret_column"))
                .andExpect(status().isBadRequest());

        final Path file = awaitAuditFile();
        final JsonNode json = readEvent(file, "greeting.list");
        assertEquals("DENIED", json.get("outcome").asText());
        assertTrue(output.getAll().contains("\"errorCode\":\"" + PARAM_INVALID_CODE + "\""),
                "JSON 日志应包含 errorCode 字段");
    }

    @Test
    void consoleShouldOutputStructuredJsonLog(final CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/v1/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        final List<String> jsonLines = output.getAll().lines()
                .map(String::trim).filter(line -> line.startsWith("{")).toList();
        assertFalse(jsonLines.isEmpty(), "应存在 JSON 结构化日志行");
        for (final String line : jsonLines) {
            final JsonNode json = MAPPER.readTree(line);
            assertTrue(json.hasNonNull("@timestamp"), "JSON 日志应含 @timestamp");
        }
        final boolean plainLogLines = output.getAll().lines()
                .anyMatch(line -> line.matches(PLAIN_LOG_PATTERN));
        assertFalse(plainLogLines, "应用日志不应再是纯文本格式");
    }

    private Path awaitAuditFile() throws Exception {
        for (int i = 0; i < 40; i++) {
            try (Stream<Path> files = Files.list(auditDir)) {
                final List<Path> hits = files
                        .filter(path -> path.getFileName().toString().startsWith("audit-"))
                        .toList();
                if (!hits.isEmpty()) {
                    return hits.get(0);
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("2 秒内未产生审计文件");
    }

    private JsonNode readEvent(final Path file, final String action) throws Exception {
        final List<String> lines = Files.readAllLines(file);
        final String line = lines.stream()
                .filter(candidate -> candidate.contains(action))
                .findFirst()
                .orElseThrow(() -> new AssertionError("审计文件缺少 action=" + action));
        return MAPPER.readTree(line);
    }
}
