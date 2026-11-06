package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.evidence.EvidenceClientProperties;
import com.ctds.common.evidence.EvidenceEvent;
import com.ctds.common.evidence.EvidenceRecorder;
import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.SharedMySqlContainer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T26（hifi §8）：客户端 ↔ 内部写面端到端契约。
 *
 * <p>用被测客户端（{@code common/evidence-client}）真实 HTTP 调用真实写面，不经过桩、不手写 JSON：
 * 验证客户端六键序列化（含 {@code occurredAt} 取 {@code Instant.toString()} 的 {@code …Z} 形式）
 * 能被写面正向接受并落一条证据。契约漂移（键名 / 时间格式）在此用例即红，不再依赖两侧手写报文。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ctds.audit.enabled=false"})
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockTestConfiguration.class)
class EvidenceClientContractIntegrationTest {

    private static final String SERVICE = "delivery-service";
    private static final String BUSINESS_NO = "W383-T26-CONTRACT";
    private static final String DID = "did:ctds:provider-0001";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_client_contract_it");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM evidence_record_action_log");
        jdbc.update("DELETE FROM evidence_record");
        jdbc.update("DELETE FROM evidence_chain_block");
        jdbc.update("DELETE FROM evidence_no_seq");
    }

    /**
     * T26：客户端真实出站报文（{@code occurredAt} 为 {@code …Z}）→ 写面接受并落库，
     * 事件时点按 UTC 原值保存（不位移、不丢弃）。
     */
    @Test
    void clientPayloadIsAcceptedByRealWriteFace() {
        final Instant occurredAt = FixedClockTestConfiguration.FIXED_INSTANT.minusSeconds(60);
        final EvidenceEvent event = new EvidenceEvent("DELIVERY", BUSINESS_NO, "DELIVERY_CREATED",
                DID, occurredAt, "S20260922000001");

        recorder().record(event);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM evidence_record WHERE business_no = ?", Long.class, BUSINESS_NO))
                .as("客户端报文应被真实写面接受（200 + code=0）并落一条证据").isEqualTo(1L);
        final Map<String, Object> record = jdbc.queryForMap(
                "SELECT stage_type, operation_type, subject_did_digest, occurred_at, content_digest "
                        + "FROM evidence_record WHERE business_no = ?", BUSINESS_NO);
        assertThat(record.get("stage_type")).isEqualTo("DELIVERY");
        assertThat(record.get("operation_type")).isEqualTo("DELIVERY_CREATED");
        assertThat(record.get("subject_did_digest").toString()).as("主体 DID 只存摘要")
                .hasSize(64).doesNotContain("did:ctds");
        assertThat(record.get("content_digest").toString()).hasSize(64);
        assertThat(((LocalDateTime) record.get("occurred_at")).toInstant(ZoneOffset.UTC))
                .as("客户端 occurredAt 的 …Z 形式被写面按 UTC 正确解析").isEqualTo(occurredAt);
    }

    private EvidenceRecorder recorder() {
        final EvidenceClientProperties properties = new EvidenceClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setRoles("evidence-internal");
        properties.setConnectTimeout(Duration.ofMillis(500));
        properties.setReadTimeout(Duration.ofSeconds(3));
        return new EvidenceRecorder(properties, SERVICE);
    }
}
