package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 参数边界集成（hifi §9 T2 / T14；规格 B1-1 / B1-2 / B6-2 口径）：
 * 环节 / 操作类型值域封闭 + 环节与操作类型必须匹配；单据号长度 5/6/64/65 与非法字符；
 * 事件时点未来 +6 分钟 / 早于 2020；主体 DID 与操作人形态；分页越界、时间窗 &gt;90 天、
 * 无任何过滤条件——一律 400 {@code 1012C0002}（对外文案统一）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockTestConfiguration.class)
class EvidenceValidationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_valid_it");
    }

    @Autowired
    private MockMvc mockMvc;

    private static String body(final String stage, final String businessNo, final String operation,
            final String did, final String occurredAt) {
        return "{\"stageType\":\"" + stage + "\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"" + operation + "\",\"subjectDid\":\"" + did
                + "\",\"occurredAt\":\"" + occurredAt + "\",\"actorSubjectNo\":\"S20260922000001\"}";
    }

    private JsonNode create(final String json) throws Exception {
        final MvcResult result = mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(json)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andExpect(status().isBadRequest())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertParamInvalid(final String reason, final String json) throws Exception {
        final JsonNode body = create(json);
        assertThat(body.path("code").asText()).as(reason).isEqualTo("1012C0002");
        assertThat(body.path("message").asText()).as("对外文案统一").isEqualTo("请求参数不合法，请检查后重试");
    }

    /** T2 / B1-2：第五类环节、未知操作类型、环节与操作类型错配一律拒绝。 */
    @Test
    void stageAndOperationValueDomainsAreClosed() throws Exception {
        assertParamInvalid("第五类环节",
                body("SETTLEMENT", "DL000081", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026-11-05T13:30:00+08:00"));
        assertParamInvalid("未知操作类型",
                body("DELIVERY", "DL000082", "DELIVERY_DELETED", "did:ctds:provider-0001",
                        "2026-11-05T13:30:00+08:00"));
        assertParamInvalid("环节与操作类型错配",
                body("CONTRACT", "CO000083", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026-11-05T13:30:00+08:00"));
        assertParamInvalid("小写环节不归一",
                body("delivery", "DL000084", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026-11-05T13:30:00+08:00"));
    }

    /** T14 / B1-1：businessNo 5/6/64/65 位与非法字符边界（6 与 64 合法）。 */
    @Test
    void businessNoLengthAndCharsetBoundaries() throws Exception {
        assertParamInvalid("5 位越下限",
                body("DELIVERY", "DL001", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026-11-05T13:30:00+08:00"));
        assertParamInvalid("65 位越上限",
                body("DELIVERY", "D" + "x".repeat(64), "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026-11-05T13:30:00+08:00"));
        assertParamInvalid("含空格",
                body("DELIVERY", "DL 00091", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026-11-05T13:30:00+08:00"));
        assertParamInvalid("含中文",
                body("DELIVERY", "交付000091", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026-11-05T13:30:00+08:00"));
        assertParamInvalid("含分号",
                body("DELIVERY", "DL0009;1", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026-11-05T13:30:00+08:00"));

        // 6 位与 64 位边界内合法（200；不同单据号避免幂等干扰）
        for (final String legal : new String[] {"DL0001", "D" + "y".repeat(63)}) {
            final MvcResult ok = mockMvc.perform(post(INTERNAL_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("DELIVERY", legal, "DELIVERY_CREATED",
                                    "did:ctds:provider-0001", "2026-11-05T13:30:00+08:00"))
                            .header("X-Ctds-Subject", "delivery-service")
                            .header("X-Ctds-Roles", "evidence-internal"))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(MAPPER.readTree(ok.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("data").path("businessNo").asText())
                    .as("边界内合法[%d 位]", legal.length()).isEqualTo(legal);
        }
    }

    /** T14：事件时点未来 +6 分钟拒绝、早于 2020 拒绝、非法格式拒绝。 */
    @Test
    void occurredAtBoundaries() throws Exception {
        assertParamInvalid("未来 +6 分钟",
                body("DELIVERY", "DL000101", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026-11-05T13:36:00Z"));
        assertParamInvalid("早于 2020",
                body("DELIVERY", "DL000102", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2019-12-31T23:59:59Z"));
        assertParamInvalid("非法格式",
                body("DELIVERY", "DL000103", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026/11/05 13:30"));
        assertParamInvalid("缺时区偏移",
                body("DELIVERY", "DL000104", "DELIVERY_CREATED", "did:ctds:provider-0001",
                        "2026-11-05T13:30:00"));
    }

    /** T14：主体 DID 形态与操作人形态封闭。 */
    @Test
    void subjectDidAndActorBoundaries() throws Exception {
        assertParamInvalid("DID 前缀错误",
                body("DELIVERY", "DL000111", "DELIVERY_CREATED", "did:example:provider-0001",
                        "2026-11-05T13:30:00+08:00"));
        assertParamInvalid("DID 过短",
                body("DELIVERY", "DL000112", "DELIVERY_CREATED", "did:ctds:abc",
                        "2026-11-05T13:30:00+08:00"));
        assertParamInvalid("操作人含非法字符",
                "{\"stageType\":\"DELIVERY\",\"businessNo\":\"DL000113\","
                        + "\"operationType\":\"DELIVERY_CREATED\","
                        + "\"subjectDid\":\"did:ctds:provider-0001\","
                        + "\"occurredAt\":\"2026-11-05T13:30:00+08:00\","
                        + "\"actorSubjectNo\":\"S 2026\"}");
        assertParamInvalid("操作人为空",
                "{\"stageType\":\"DELIVERY\",\"businessNo\":\"DL000114\","
                        + "\"operationType\":\"DELIVERY_CREATED\","
                        + "\"subjectDid\":\"did:ctds:provider-0001\","
                        + "\"occurredAt\":\"2026-11-05T13:30:00+08:00\","
                        + "\"actorSubjectNo\":\"\"}");
    }

    /** T14 / B6-2 口径：清单无过滤条件、分页越界、时间窗 &gt;90 天一律 400 1012C0002。 */
    @Test
    void listingBoundariesRejected() throws Exception {
        final String[] queries = {
            "",
            "?pageSize=101",
            "?pageNum=10001",
            "?stageType=DELIVERY&from=2026-01-01T00:00:00Z&to=2026-04-15T00:00:00Z",
            "?stageType=DELIVERY&from=2026-04-15T00:00:00Z&to=2026-04-01T00:00:00Z",
            "?stageType=UNKNOWN",
            "?status=UNKNOWN",
        };
        for (final String query : queries) {
            final MvcResult result = mockMvc.perform(
                            get("/api/v1/evidence/records" + query)
                                    .header("X-Ctds-Subject", "S-admin-evidence")
                                    .header("X-Ctds-Roles", "admin"))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            assertThat(MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("code").asText()).as(query).isEqualTo("1012C0002");
        }
    }
}
