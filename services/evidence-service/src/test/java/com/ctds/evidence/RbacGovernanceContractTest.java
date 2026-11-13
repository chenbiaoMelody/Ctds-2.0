package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.2 T3 / T4：授予 / 收回面、映射变更留痕、幂等与最小码值集。
 * 依据 hifi §2 W1/W2/R1/R2、§3 数据模型最小集、§4 幂等与并发、§9 E6 承载口径。
 */
class RbacGovernanceContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();
    private static final Path SERVICE = ROOT.resolve("services/evidence-service");
    private static final Path MAIN = SERVICE.resolve("src/main/java/com/ctds/evidence");

    @Test
    void v3MigrationCreatesMappingAndChangeLogTables() throws IOException {
        Path migration = SERVICE.resolve("src/main/resources/db/migration/V3__create_rbac_tables.sql");
        assertThat(Files.exists(migration)).as("Q1-A：映射表与留痕表须由 V3 迁移创建").isTrue();
        String sql = Files.readString(migration).toUpperCase();
        assertThat(sql).contains("CREATE TABLE");
        assertThat(sql).contains("ROLE_PERMISSION_CHANGE_LOG");
        assertThat(sql).contains("CHANGE_NO");
        assertThat(sql).contains("CHANGED_BY");
        assertThat(sql).contains("BEFORE_VALUE");
        assertThat(sql).contains("AFTER_VALUE");
        assertThat(sql).contains("UNIQUE KEY");
    }

    @Test
    void governanceControllerExposesFourContractEndpoints() throws IOException {
        Path controller = MAIN.resolve("interfaces/RbacGovernanceController.java");
        assertThat(Files.exists(controller)).as("W1/W2/R1/R2 须落在映射表持有服务").isTrue();
        String text = Files.readString(controller);
        assertThat(text).contains("@PostMapping");
        assertThat(text).contains("@GetMapping");
        assertThat(text).contains("@RequirePermission(\"connector.review\")");
        assertThat(text).contains("@RequirePermission(\"connector.health.read\")");
        assertThat(text).contains("@RequirePermission(\"evidence.governance\")");
    }

    @Test
    void grantAndRevokeAreTransactionalAndIdempotent() throws IOException {
        Path service = MAIN.resolve("application/RbacPermissionService.java");
        assertThat(Files.exists(service)).isTrue();
        String text = Files.readString(service);
        assertThat(text).contains("@Transactional");
        assertThat(text).contains("changeNo");
        assertThat(text).contains("grant");
        assertThat(text).contains("revoke");
    }

    @Test
    void errorCodesUseReserved1013SegmentOnly() throws IOException {
        Path codes = MAIN.resolve("domain/RbacErrorCodes.java");
        assertThat(Files.exists(codes)).as("Q7-A：1013 段最小码值集须落定").isTrue();
        String text = Files.readString(codes);
        assertThat(text).contains("1013C0001");
        assertThat(text).contains("1013S0001");
        assertThat(text).doesNotContain("1011C", "1012C");
    }
}
