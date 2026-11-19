package com.ctds.common.guardrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiCallLogDdlReserveTest {

    private static final List<String> EXPECTED_COLUMNS = List.of(
            "id", "trace_id", "feature", "actor", "provider", "model",
            "prompt_tokens", "completion_tokens", "latency_ms", "outcome", "error_code",
            "guardrail_verdict", "guardrail_reason", "created_at");

    private static final Pattern COLUMN_PATTERN =
            Pattern.compile("^\\s{4}([a-z_]+)\\s+(BIGINT|VARCHAR|INT|DATETIME)", Pattern.MULTILINE);

    @Test
    @DisplayName("T10 DDL 预留边界：14 列、两索引、零内容列、禁止建表声明")
    void reservedDdlShouldMatchContract() throws IOException {
        final Path ddl = repoRoot().resolve(Path.of("deploy", "sql", "ai-call-log.sql"));
        assertTrue(Files.isRegularFile(ddl), "reserved DDL must exist: " + ddl);
        final String sql = Files.readString(ddl, StandardCharsets.UTF_8);

        final List<String> columns = new ArrayList<>();
        final Matcher matcher = COLUMN_PATTERN.matcher(sql);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        assertEquals(EXPECTED_COLUMNS, columns);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS ai_call_log"));
        assertTrue(sql.contains("KEY idx_ai_call_log_trace (trace_id)"));
        assertTrue(sql.contains("KEY idx_ai_call_log_created (created_at)"));
        assertTrue(sql.contains("仅表结构预留"));
        assertTrue(sql.contains("禁止在 V1.0 建表或写入"));
        assertTrue(sql.contains("V1.5 由 C-8.4 / C-11.5 填充归属服务与 Flyway 实施"));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("INSERT INTO"));
    }

    @Test
    @DisplayName("T10 V1.0 零迁移：services 下无 ai_call_log 迁移脚本")
    void servicesShouldHaveNoAiCallLogMigration() throws IOException {
        final Path migrationRoot = repoRoot().resolve("services");
        final List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(migrationRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.toAbsolutePath().toString()
                            .replace('\\', '/').contains("/src/main/resources/db/migration/"))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .replace("-", "_").contains("ai_call_log"))
                    .forEach(path -> hits.add(path.toString()));
        }
        assertEquals(List.of(), hits);
    }

    private static Path repoRoot() {
        return Path.of("..", "..").normalize().toAbsolutePath();
    }
}