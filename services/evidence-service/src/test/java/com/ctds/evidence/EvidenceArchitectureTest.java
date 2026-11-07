package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 不可改删的结构性断言（hifi §9 T9；规格 B2-3 / B2-4 / B4-4）：
 * ① 仓储端口无改内容方法、无删除方法；② 状态更新 SQL 只允许触碰状态列；
 * ③ 账本实现只 insert + 按 txId 查询（无 UPDATE / DELETE）；④ 接口层无 PUT / PATCH / DELETE 端点。
 * 以源码文本断言承载（"入口不存在"必须机器可证，不能只靠人眼）。
 */
class EvidenceArchitectureTest {

    private static final Path DOMAIN = Path.of("src/main/java/com/ctds/evidence/domain");
    private static final Path INFRA = Path.of("src/main/java/com/ctds/evidence/infrastructure");
    private static final Path INTERFACES = Path.of("src/main/java/com/ctds/evidence/interfaces");

    @Test
    void repositoryPortExposesOnlyAppendAndStatusMethods() throws IOException {
        final String source = Files.readString(DOMAIN.resolve("EvidenceRepository.java"));
        assertThat(source).contains("void insert(", "void updateStatus(", "void appendAction(",
                "Optional<EvidenceRecord> findByNo(", "EvidencePage page(");
        assertThat(source).doesNotContainIgnoringCase("delete")
                .as("仓储端口无删除入口")
                .doesNotContainIgnoringCase("remove")
                .doesNotContainIgnoringCase("purge")
                .doesNotContain("updateContent");
    }

    @Test
    void statusUpdateSqlTouchesOnlyStatusColumns() throws IOException {
        final String source = Files.readString(INFRA.resolve("JdbcEvidenceRepository.java"));
        final int start = source.indexOf("UPDATE evidence_record SET");
        final int end = source.indexOf("WHERE id = ?", start);
        assertThat(start).as("存在状态更新语句").isPositive();
        assertThat(end).isGreaterThan(start);
        final String setClause = source.substring(start, end);
        assertThat(setClause).as("允许更新的状态列")
                .contains("status = ?", "chain_tx_id = ?", "chain_block_no = ?", "retry_count = ?",
                        "last_error_summary = ?", "updated_at = ?");
        for (final String contentColumn : List.of("evidence_no", "stage_type", "business_no",
                "operation_type", "subject_did_digest", "occurred_at", "content_digest")) {
            assertThat(setClause).as("内容列不得出现在 UPDATE 子句：%s", contentColumn)
                    .doesNotContain(contentColumn + " =");
        }
    }

    @Test
    void ledgerImplementationIsAppendOnly() throws IOException {
        final String source = Files.readString(INFRA.resolve("LocalLedgerEvidenceGateway.java"));
        assertThat(source).doesNotContain("UPDATE evidence_chain_block")
                .doesNotContain("DELETE FROM")
                .doesNotContainIgnoringCase("delete from evidence");
        assertThat(source).contains("INSERT INTO evidence_chain_block");
    }

    @Test
    void interfacesExposeNoMutableHttpVerbs() throws IOException {
        try (Stream<Path> files = Files.walk(INTERFACES)) {
            for (final Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                final String source = Files.readString(file);
                assertThat(source).as("接口层无改删端点：%s", file.getFileName())
                        .doesNotContain("@PutMapping", "@PatchMapping", "@DeleteMapping");
            }
        }
    }

    /** WBS-3.8.4 T21 / hifi §4.3：审计留痕与报告登记仓储 append-only（端口与实现均无改删入口）。 */
    @Test
    void auditAppendOnlyRepositoriesExposeNoMutation() throws IOException {
        for (final String port : List.of("AccessLogRepository.java", "AuditReportRepository.java")) {
            final String source = Files.readString(DOMAIN.resolve(port));
            assertThat(source).as("审计仓储端口无改删入口：%s", port)
                    .doesNotContainIgnoringCase("delete")
                    .doesNotContain("updateStatus")
                    .doesNotContain("UPDATE ");
        }
        for (final String infra : List.of("JdbcAccessLogRepository.java",
                "JdbcAuditReportRepository.java")) {
            final String source = Files.readString(INFRA.resolve(infra));
            assertThat(source).as("审计仓储实现含插入语句：%s", infra)
                    .contains("INSERT INTO");
            assertThat(source).as("审计仓储实现无改删语句：%s", infra)
                    .doesNotContain("UPDATE ")
                    .doesNotContainIgnoringCase("delete from");
        }
        final String auditQuery = Files.readString(
                INFRA.resolve("JdbcEvidenceAuditQueryRepository.java"));
        assertThat(auditQuery).as("审计查询仓储纯只读（零写入语句）")
                .doesNotContain("INSERT INTO")
                .doesNotContain("UPDATE ")
                .doesNotContainIgnoringCase("delete from");
        final String auditPort = Files.readString(
                DOMAIN.resolve("EvidenceAuditQueryRepository.java"));
        assertThat(auditPort).as("审计查询端口纯只读（无写入方法）")
                .doesNotContainIgnoringCase("insert")
                .doesNotContainIgnoringCase("delete")
                .doesNotContainIgnoringCase("update");
    }
}
