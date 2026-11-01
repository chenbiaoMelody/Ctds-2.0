package com.ctds.metering;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.metering.domain.MeteringFlow;
import com.ctds.metering.domain.MeteringFlowRepository;
import com.ctds.metering.domain.MeteringSourceType;
import com.ctds.metering.support.SharedMySqlContainer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 迁移结构锚（hifi §8 T9，沿 3.7.4 T9 同型）：V1 单表 + uk_metering_source 幂等锚 +
 * idx_flow_contract 聚合索引；列面 = ADR-022 §2.6-2 最小必要投影（零请求体/响应体/原文/令牌列
 * ——information_schema 全列断言）；uk 重放幂等（同 source_no 二次 upsert 零重计）。
 * 真实 MySQL 8 容器实跑 Flyway V1；本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class MeteringMigrationStructureIntegrationTest {

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_structure_it");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeteringFlowRepository flowRepository;

    @Test
    void meteringFlowTableExists() {
        final List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() ORDER BY table_name", String.class);
        assertThat(tables).as("V1 单表落库（flyway_schema_history 为迁移自记表）")
                .contains("metering_flow");
    }

    @Test
    void columnsAreMinimalNecessaryProjection() {
        // 列面白名单 = hifi §2 DDL 全集（ADR-022 §2.6-2 契约字段投影）；集合相等 = 零敏感原文列
        final List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'metering_flow' "
                        + "ORDER BY ordinal_position", String.class);
        assertThat(columns).containsExactly("id", "contract_no", "requester_no", "source_type",
                "source_no", "action", "resource", "occurred_at", "created_at");
    }

    @Test
    void ukIdempotentAnchorAndAggregateIndexExist() {
        final List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
                "SELECT index_name, non_unique, column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'metering_flow' "
                        + "AND index_name IN ('uk_metering_source', 'idx_flow_contract') "
                        + "ORDER BY index_name, seq_in_index");
        assertThat(indexes).extracting(i -> i.get("index_name") + ":" + i.get("column_name"))
                .containsExactly(
                        "idx_flow_contract:contract_no", "idx_flow_contract:occurred_at",
                        "uk_metering_source:source_type", "uk_metering_source:source_no");
        assertThat(indexes).allSatisfy(i ->
                assertThat(((Number) i.get("non_unique")).intValue())
                        .isEqualTo("uk_metering_source".equals(i.get("index_name")) ? 0 : 1));
    }

    @Test
    void sameSourceNoReplayStaysSingleRow() {
        // T1/T10 幂等锚：同 uk(source_type, source_no) 二次 upsert 零重计（真库语义实证）
        final MeteringFlow flow = new MeteringFlow("CO900001", "S-req-9", MeteringSourceType.GATEWAY_API,
                "evt-replay-1", "gateway.delivery.metering", "delivery-catalog-products GET /x",
                LocalDateTime.of(2026, 10, 11, 9, 0));
        assertThat(flowRepository.upsert(flow)).as("首次落库 = 新增").isTrue();
        assertThat(flowRepository.upsert(flow)).as("重放 = uk 空转零重计").isFalse();
        assertThat(flowRepository.countByContract("CO900001")).isEqualTo(1);
    }
}
