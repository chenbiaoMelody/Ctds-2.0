package com.ctds.connector.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 连接器服务集成测试基类（hifi §9）：真实 MySQL 容器 + 主体/DID 本地 stub + 全类共享 Spring 上下文。
 * 每个用例前清空三张业务表并重置取号表，保证用例互不串扰（同库共享，避免多上下文启动开销）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
public abstract class ConnectorTestSupport {

    protected static final String APPLICANT_ROLE = "applicant";
    protected static final String ADMIN_ROLE = "admin";
    protected static final String INTERNAL_ROLE = "connector-internal";
    protected static final String REGISTRATIONS = "/api/v1/connectors/registrations";
    protected static final String OPERATOR = "ops-admin";

    private static final ConnectorStubServer STUB = createStub();

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_connector_it");
        registry.add("ctds.connector.subject.base-url", STUB::baseUrl);
        registry.add("ctds.connector.did.base-url", STUB::baseUrl);
        registry.add("ctds.connector.health.offline-after", () -> "5m");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM connector_did_binding");
        jdbc.update("DELETE FROM connector_registration");
        jdbc.update("DELETE FROM connector_action_log");
        jdbc.update("UPDATE connector_application_no_seq SET next_no = 1");
        jdbc.update("UPDATE connector_no_seq SET next_no = 1");
        STUB.reset();
    }

    protected static ConnectorStubServer stub() {
        return STUB;
    }

    /** 断言 DATA 段（code=0）。 */
    protected JsonNode data(final MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    protected JsonNode whole(final MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected static String registrationBody(final String name) {
        return "{\"connectorName\":\"" + name + "\",\"connectorType\":\"STANDARD\","
                + "\"connectorVersion\":\"1.0.0\"}";
    }

    /** 提交申请并返回 applicationNo。 */
    protected String apply(final String subjectNo, final String name) throws Exception {
        final MvcResult result = mockMvc.perform(post(REGISTRATIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", subjectNo)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(registrationBody(name)))
                .andExpect(status().isOk())
                .andReturn();
        return data(result).path("applicationNo").asText();
    }

    /** 申请 → 核验通过，返回 connectorNo。 */
    protected String registerConnector(final String subjectNo, final String name) throws Exception {
        final String applicationNo = apply(subjectNo, name);
        mockMvc.perform(post(REGISTRATIONS + "/" + applicationNo + "/verification")
                        .header("X-Ctds-Subject", OPERATOR)
                        .header("X-Ctds-Roles", ADMIN_ROLE))
                .andExpect(status().isOk());
        return jdbc.queryForObject(
                "SELECT connector_no FROM connector_registration WHERE application_no = ?",
                String.class, applicationNo);
    }

    private static ConnectorStubServer createStub() {
        try {
            final ConnectorStubServer stub = new ConnectorStubServer();
            // 全类共享静态 stub（避免多上下文启动开销），JVM 退出时释放监听端口与调度线程。
            Runtime.getRuntime().addShutdownHook(new Thread(stub::stop, "connector-stub-shutdown"));
            return stub;
        } catch (final IOException e) {
            throw new IllegalStateException("测试 stub 启动失败", e);
        }
    }
}
