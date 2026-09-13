package com.ctds.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 渠道异常 fail-fast 出站封套（规格行为 3 第 5 条 + hifi B8）：注入模拟渠道异常后，
 * 认证请求返回 503 + 1004S0001 + 业务文案"认证服务暂不可用，请稍后重试"
 * （本地处理器精确映射，不落入全局 S 型脱敏；文案与码值即规格要求的明确业务错误）。
 * 异常注入覆盖全部渠道调用，OCR 上传路径即触发（核验另有前置校验，先于渠道调用拦截）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "ctds.std.certification.mock.channel-error=true")
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CertChannelUnavailableIntegrationTest {

    private static final String BASE = "/api/v1/subject/registrations";
    private static final String APPLICANT = "applicant-01";
    private static Path keyFile;

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_subject");

    @BeforeAll
    static void createKeyFile() throws Exception {
        keyFile = Files.createTempFile("ctds-test-keys", ".keys");
        Files.writeString(keyFile, "subject-cert-material=MDEyMzQ1Njc4OWFiY2RlZg==\n",
                StandardCharsets.UTF_8);
    }

    @AfterAll
    static void deleteKeyFile() throws Exception {
        Files.deleteIfExists(keyFile);
    }

    @DynamicPropertySource
    static void testProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.crypto.local.key-file", () -> keyFile.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void channelErrorReturnsServiceUnavailableWithBusinessMessage() throws Exception {
        final String subjectNo = registerSubject();

        mockMvc.perform(multipart(BASE + "/" + subjectNo + "/certification/license")
                        .file(new MockMultipartFile("file", "A1.jpg", MediaType.IMAGE_JPEG_VALUE,
                                "image".getBytes(StandardCharsets.US_ASCII)))
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("1004S0001"))
                .andExpect(jsonPath("$.message").value("认证服务暂不可用，请稍后重试"));

        // 无脏数据残留（规格行为 3 GWT-4 / 评审视角 4 补齐）：渠道异常时材料与渠道留痕均不落半成品
        final Integer materials = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM cert_material", Integer.class);
        assertThat(materials).isZero();
        final Integer channelErrors = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM cert_verification_log WHERE conclusion = 'CHANNEL_ERROR' AND counted = 0",
                Integer.class);
        assertThat(channelErrors).isEqualTo(1);
    }

    private String registerSubject() throws Exception {
        final MvcResult mvcResult = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"渠道异常演示公司\",\"uscc\":\"91330100MA27X8AB0A\","
                                + "\"subjectType\":\"ENTERPRISE\",\"regAddress\":\"杭州市XX区XX路88号\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13800001234\","
                                + "\"adminAccount\":\"admin001\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return new ObjectMapper().readTree(mvcResult.getResponse().getContentAsString())
                .get("data").get("subjectNo").asText();
    }
}
