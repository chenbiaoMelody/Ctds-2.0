package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.2 T6 / DB-120 ⑤：健康总览改挂只读点 connector.health.read，核验写面仍 connector.review。
 * 依据规格 B6-R3、hifi §2 R3 与 §9 E2（新增权限点仅此一个）。
 */
class ConnectorHealthReadContractTest {

    private static final Path SERVICE = Path.of("").toAbsolutePath();
    private static final Path MAIN = SERVICE.resolve("src/main/java/com/ctds/connector");

    @Test
    void healthOverviewUsesDedicatedReadOnlyPoint() throws IOException {
        String text = Files.readString(
                MAIN.resolve("interfaces/ConnectorGovernanceController.java"));
        int mapping = text.indexOf("/governance/connectors");
        assertThat(mapping).isPositive();
        assertThat(text.substring(mapping)).contains("@RequirePermission(\"connector.health.read\")");
        assertThat(text.substring(mapping, Math.min(mapping + 320, text.length())))
                .doesNotContain("@RequirePermission(\"connector.review\")");
    }

    @Test
    void verificationWriteStillUsesConnectorReview() throws IOException {
        String text = Files.readString(
                MAIN.resolve("interfaces/ConnectorRegistrationController.java"));
        assertThat(text).contains("@RequirePermission(\"connector.review\")");
        assertThat(text).doesNotContain("connector.health.read");
    }

    @Test
    void newPointIsRegisteredInConnectorYml() throws IOException {
        String yml = Files.readString(SERVICE.resolve("src/main/resources/application.yml"));
        assertThat(yml).contains("connector.health.read");
        assertThat(yml).contains("platform.sysadmin");
    }
}
