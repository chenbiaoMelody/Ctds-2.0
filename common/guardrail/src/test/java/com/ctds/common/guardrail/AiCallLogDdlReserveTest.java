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
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiCallLogDdlReserveTest {

    private static final List<String> EXPECTED_COLUMNS = List.of(
            "id", "trace_id", "feature", "actor", "provider", "model",
            "prompt_tokens", "completion_tokens", "latency_ms", "outcome", "error_code",
            "guardrail_verdict", "guardrail_reason", "created_at");

    private static final List<String> FORBIDDEN_COLUMNS = List.of(
            "prompt", "prompt_text", "messages", "message", "response", "content",
            "request", "payload", "secret", "password", "key");

    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", "target", "node_modules", "build-output", "dist");

    @Test
    @DisplayName("T10 DDL 预留边界：14 列、两索引、零内容列、禁止建表声明")
    void reservedDdlShouldMatchContract() throws IOException {
        final Path ddl = repoRoot().resolve(Path.of("deploy", "sql", "ai-call-log.sql"));
        assertTrue(Files.isRegularFile(ddl), "reserved DDL must exist: " + ddl);
        final String sql = Files.readString(ddl, StandardCharsets.UTF_8);

        final List<String> columns = parseColumns(sql);
        assertEquals(EXPECTED_COLUMNS, columns);
        for (final String column : columns) {
            assertFalse(FORBIDDEN_COLUMNS.contains(column), "content column must not exist: " + column);
        }

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS ai_call_log"));
        assertTrue(sql.contains("KEY idx_ai_call_log_trace (trace_id)"));
        assertTrue(sql.contains("KEY idx_ai_call_log_created (created_at)"));
        assertTrue(sql.contains("SUCCESS / DENIED / FAILURE"));
        assertTrue(sql.contains("ALLOW / DENY"));
        assertTrue(sql.contains("仅表结构预留"));
        assertTrue(sql.contains("禁止在 V1.0 建表或写入"));
        assertTrue(sql.contains("V1.5 由 C-8.4 / C-11.5 填充归属服务与 Flyway 实施"));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("INSERT INTO"));
    }

    @Test
    @DisplayName("T10 V1.0 零迁移：全仓无 ai_call_log 迁移脚本")
    void repositoryShouldHaveNoAiCallLogMigration() throws IOException {
        final List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot())) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !isSkipped(path))
                    .filter(path -> path.toAbsolutePath().toString()
                            .replace('\\', '/').contains("/src/main/resources/db/migration/"))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .replace("-", "_").contains("ai_call_log"))
                    .forEach(path -> hits.add(repoRoot().relativize(path).toString()));
        }
        assertEquals(List.of(), hits);
    }

    private static List<String> parseColumns(final String sql) {
        final List<String> columns = new ArrayList<>();
        for (final String rawLine : sql.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith(",")) {
                line = line.substring(1).trim();
            }
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("CREATE TABLE")
                    || line.startsWith("PRIMARY KEY") || line.startsWith("KEY ")
                    || line.startsWith("CONSTRAINT") || line.startsWith(")")) {
                continue;
            }
            columns.add(line.split("\\s+")[0]);
        }
        return columns;
    }

    private static boolean isSkipped(final Path path) {
        for (final Path part : repoRoot().relativize(path.toAbsolutePath())) {
            if (SKIPPED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static Path repoRoot() {
        return Path.of("..", "..").normalize().toAbsolutePath();
    }
}