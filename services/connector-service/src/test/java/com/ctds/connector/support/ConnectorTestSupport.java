package com.ctds.connector.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.infrastructure.SourceAdapterRegistry;
import com.ctds.connector.sdk.adapter.gov.GovExchangeSourceAdapter;
import com.ctds.connector.sdk.spi.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 每个用例前清空业务表并重置取号表，保证用例互不串扰（同库共享，避免多上下文启动开销）。
 * 3.6.5 起：临时密钥文件供源凭据国密（解析路径不落库、不打印）+ 源表清理 + 模拟源计数重置。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false",
                // 仅测试放开回环 / 内网地址（生产默认拒绝，见 SourceEndpointPolicy；502 探测用例需本地桩）
                "ctds.connector.source.allow-private-endpoint=true"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
public abstract class ConnectorTestSupport {

    protected static final String APPLICANT_ROLE = "applicant";
    protected static final String ADMIN_ROLE = "admin";
    protected static final String INTERNAL_ROLE = "connector-internal";
    protected static final String REGISTRATIONS = "/api/v1/connectors/registrations";
    protected static final String CONNECTORS = "/api/v1/connectors/";
    protected static final String INTERNAL = "/api/v1/connectors/internal/";
    protected static final String OPERATOR = "ops-admin";
    /** 源凭据测试明文（断言：任何响应 / 落库 / 留痕均不得出现）。 */
    protected static final String SOURCE_SECRET = "gov-exchange-secret-0001";
    /** 源凭据加密 keyRef（与 application.yml 默认一致）。 */
    protected static final String SOURCE_KEY_REF = "connector-source-credential";

    private static final ConnectorStubServer STUB = createStub();
    private static final Path KEY_FILE = createKeyFile();

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_connector_it");
        registry.add("ctds.connector.subject.base-url", STUB::baseUrl);
        registry.add("ctds.connector.did.base-url", STUB::baseUrl);
        registry.add("ctds.connector.health.offline-after", () -> "5m");
        registry.add("ctds.crypto.local.key-file", KEY_FILE::toString);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected SourceAdapterRegistry sourceAdapterRegistry;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM connector_source");
        jdbc.update("DELETE FROM connector_job_occupancy");
        jdbc.update("DELETE FROM connector_version_change");
        jdbc.update("DELETE FROM connector_did_binding");
        jdbc.update("DELETE FROM connector_registration");
        jdbc.update("DELETE FROM connector_action_log");
        jdbc.update("UPDATE connector_application_no_seq SET next_no = 1");
        jdbc.update("UPDATE connector_no_seq SET next_no = 1");
        jdbc.update("UPDATE connector_upgrade_no_seq SET next_no = 1");
        jdbc.update("UPDATE connector_source_no_seq SET next_no = 1");
        govAdapter().mockResetCounters();
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

    /** 升级登记（期望 200），返回 data 段。 */
    protected JsonNode upgrade(final String subjectNo, final String role, final String connectorNo,
            final String toVersion, final String compatibility) throws Exception {
        final MvcResult result = mockMvc.perform(post(CONNECTORS + connectorNo + "/upgrades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", subjectNo)
                        .header("X-Ctds-Roles", role)
                        .content("{\"toVersion\":\"" + toVersion + "\",\"compatibility\":\""
                                + compatibility + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    /** 注销（期望 200），返回 data 段。 */
    protected JsonNode cancel(final String subjectNo, final String role, final String connectorNo)
            throws Exception {
        final MvcResult result = mockMvc.perform(post(CONNECTORS + connectorNo + "/cancellation")
                        .header("X-Ctds-Subject", subjectNo)
                        .header("X-Ctds-Roles", role))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    /** 内部面登记在途作业占用（期望 200）。 */
    protected void occupy(final String connectorNo, final String jobRef) throws Exception {
        mockMvc.perform(post(INTERNAL + connectorNo + "/occupancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content("{\"jobRef\":\"" + jobRef + "\"}"))
                .andExpect(status().isOk());
    }

    /** 登记源（期望 200），返回 sourceNo。 */
    protected String registerSource(final String subjectNo, final String connectorNo, final String sourceType,
            final String endpoint, final String credential) throws Exception {
        final MvcResult result = mockMvc.perform(post(CONNECTORS + connectorNo + "/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", subjectNo)
                        .header("X-Ctds-Roles", APPLICANT_ROLE)
                        .content(sourceBody(sourceType, endpoint, credential)))
                .andExpect(status().isOk())
                .andReturn();
        return data(result).path("sourceNo").asText();
    }

    /** 源登记请求体（凭据仅出现在请求侧）。 */
    protected static String sourceBody(final String sourceType, final String endpoint,
            final String credential) {
        return "{\"sourceType\":\"" + sourceType + "\",\"endpoint\":\"" + endpoint + "\",\"credential\":\""
                + credential + "\"}";
    }

    /** 政务适配器实例（模拟源观测面：触达计数 / 源端数据版本）。 */
    protected GovExchangeSourceAdapter govAdapter() {
        return (GovExchangeSourceAdapter) sourceAdapterRegistry.find(SourceType.GOV_EXCHANGE).orElseThrow();
    }

    /** 内部读取请求体。 */
    protected static String readBody(final String contractNo, final String purpose) {
        return "{\"contractNo\":\"" + contractNo + "\",\"purpose\":\"" + purpose + "\"}";
    }

    private static Path createKeyFile() {
        try {
            final Path file = Files.createTempFile("ctds-connector-source-keys", ".keys");
            Files.writeString(file, SOURCE_KEY_REF + "=MDEyMzQ1Njc4OWFiY2RlZg==\n", StandardCharsets.UTF_8);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(file);
                } catch (final IOException ignored) {
                    // 临时密钥文件清理失败不影响测试结论（不落库、不打印内容）
                }
            }, "connector-source-key-cleanup"));
            return file;
        } catch (final IOException e) {
            throw new IllegalStateException("测试密钥文件创建失败", e);
        }
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
