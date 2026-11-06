package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 存证写入集成（hifi §9 T1~T4 / T12 / T13）：
 * ① 记录六要素落库 + 同步上链（ON_CHAIN / txId / 账本恰一行）；
 * ② 哈希链前序链接；③ 三元组幂等（重复返回原记录、零新行零新留痕）；
 * ④ 编号当日递增不回收；⑤ 上链载荷仅白名单五项（负向字符串扫描）。
 * Docker 缺失整类跳过（沿既有集成用例口径）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockTestConfiguration.class)
class EvidenceWriteIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String INTERNAL_SUBJECT = "delivery-service";
    private static final String INTERNAL_ROLES = "evidence-internal";
    private static final String OCCURRED_AT = "2026-11-05T13:30:00+08:00";
    private static final String DID = "did:ctds:provider-0001";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_write_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM evidence_record_action_log");
        jdbc.update("DELETE FROM evidence_record");
        jdbc.update("DELETE FROM evidence_chain_block");
        jdbc.update("DELETE FROM evidence_no_seq");
    }

    private static String body(final String stage, final String businessNo, final String operation,
            final String did, final String occurredAt) {
        return "{\"stageType\":\"" + stage + "\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"" + operation
                + "\",\"subjectDid\":\"" + did + "\",\"occurredAt\":\"" + occurredAt
                + "\",\"actorSubjectNo\":\"S20260922000001\"}";
    }

    private JsonNode create(final String businessNo, final ResultMatcher expected) throws Exception {
        final MvcResult result = mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DELIVERY", businessNo, "DELIVERY_CREATED", DID, OCCURRED_AT))
                        .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                        .header("X-Ctds-Roles", INTERNAL_ROLES))
                .andExpect(expected)
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** T4 / B1-1：创建即同步上链，记录与账本各一行、交易标识与块号回填。 */
    @Test
    void createPersistsRecordAndChainBlock() throws Exception {
        final JsonNode data = create("DL000031", status().isOk()).path("data");

        assertThat(data.path("evidenceNo").asText()).as("编号 = EV + 日期 + 6 位当日序列")
                .isEqualTo("EV20261105000001");
        assertThat(data.path("stageType").asText()).isEqualTo("DELIVERY");
        assertThat(data.path("businessNo").asText()).as("单据号逐字透传").isEqualTo("DL000031");
        assertThat(data.path("operationType").asText()).isEqualTo("DELIVERY_CREATED");
        assertThat(data.path("status").asText()).isEqualTo("ON_CHAIN");
        assertThat(data.path("chainTxId").asText()).isEqualTo("LTX000000000001");
        assertThat(data.path("duplicate").asBoolean()).isFalse();
        assertThat(data.path("occurredAt").asText()).isEqualTo("2026-11-05T05:30:00.000Z");

        final Map<String, Object> record = jdbc.queryForMap(
                "SELECT * FROM evidence_record WHERE evidence_no = 'EV20261105000001'");
        assertThat(record.get("content_digest").toString()).hasSize(64);
        assertThat(record.get("subject_did_digest").toString()).hasSize(64);
        assertThat(record.get("subject_did_digest").toString()).as("不存 DID 原值")
                .doesNotContain("did:ctds");
        assertThat(record.get("chain_block_no")).isEqualTo(1L);
        assertThat(record.get("retry_count")).isEqualTo(0);
        assertThat(record.get("last_error_summary")).isNull();

        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_chain_block", Long.class))
                .as("账本恰一行").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT prev_block_hash FROM evidence_chain_block", String.class))
                .as("创世块前序哈希 = 64 个 0").isEqualTo("0".repeat(64));

        final List<String> actions = jdbc.queryForList(
                "SELECT action FROM evidence_record_action_log ORDER BY id", String.class);
        assertThat(actions).containsExactly("CREATED", "CHAIN_SUCCEEDED");
    }

    /** T1 / B2-2：同三元组重复创建 → 返回原记录、duplicate=true、零新行、零新留痕。 */
    @Test
    void duplicateTripleReturnsOriginalWithoutNewRows() throws Exception {
        final JsonNode first = create("DL000032", status().isOk()).path("data");
        final JsonNode second = create("DL000032", status().isOk()).path("data");

        assertThat(second.path("evidenceNo").asText()).isEqualTo(first.path("evidenceNo").asText());
        assertThat(second.path("duplicate").asBoolean()).isTrue();
        assertThat(second.path("status").asText()).isEqualTo("ON_CHAIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_record", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_record_action_log", Long.class))
                .as("重复触发零新留痕").isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_chain_block", Long.class))
                .as("重复触发零新账本块").isEqualTo(1L);
    }

    /** T13 / B1-1：同日 3 条编号互异、序列递增；取号不回收（值持续增长）。 */
    @Test
    void evidenceNumbersAreUniqueAndIncreasing() throws Exception {
        final List<String> numbers = new ArrayList<>();
        for (final String businessNo : List.of("DL000041", "DL000042", "DL000043")) {
            numbers.add(create(businessNo, status().isOk()).path("data").path("evidenceNo").asText());
        }
        assertThat(numbers).containsExactly("EV20261105000001", "EV20261105000002", "EV20261105000003");
        assertThat(jdbc.queryForObject("SELECT next_val FROM evidence_no_seq WHERE seq_date = '20261105'",
                Integer.class)).as("取号表只增不回收").isEqualTo(3);
    }

    /** T4 / B1-1：第二条账本块的前序哈希 = 第一条块哈希（哈希链链接）。 */
    @Test
    void chainLinksPreviousBlockHash() throws Exception {
        create("DL000051", status().isOk());
        create("DL000052", status().isOk());

        final List<Map<String, Object>> blocks = jdbc.queryForList(
                "SELECT block_no, block_hash, prev_block_hash FROM evidence_chain_block ORDER BY block_no");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).get("prev_block_hash")).isEqualTo("0".repeat(64));
        assertThat(blocks.get(1).get("prev_block_hash"))
                .as("第二块前序 = 第一块哈希").isEqualTo(blocks.get(0).get("block_hash"));
        assertThat(blocks.get(1).get("block_no")).isEqualTo(2L);
    }

    /** T12 / B1-4：上链载荷仅五项、固定键序，无 DID 原值 / 无原文 / 无密钥材料。 */
    @Test
    void payloadContainsExactlyFiveWhitelistedFields() throws Exception {
        create("DL000061", status().isOk());
        final String payloadJson = jdbc.queryForObject(
                "SELECT payload_json FROM evidence_chain_block", String.class);

        final List<String> keys = new ArrayList<>();
        MAPPER.readTree(payloadJson).fieldNames().forEachRemaining(keys::add);
        assertThat(keys).as("固定键序五项").containsExactly("businessNo", "occurredAt", "operationType",
                "subjectDidDigest", "contentDigest");
        assertThat(payloadJson).as("无 DID 原值").doesNotContain("did:ctds").doesNotContain(DID);
        assertThat(payloadJson).as("不含存证编号 / 环节类型 / 状态（只本地留痕）")
                .doesNotContain("EV2026").doesNotContain("DELIVERY\"");
        assertThat(payloadJson).as("无密钥材料 / 个人信息 / 原文标记")
                .doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("secret")
                .doesNotContain("PRIVATE KEY").doesNotContain("BEGIN");
    }
}
