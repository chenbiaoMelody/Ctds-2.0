package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.2 E6-A：运行期切库表的部署承载守卫。
 * 六份有数据源、有权限映射的 k8s 清单必须同时注入 source=jdbc 与 schema=ctds_evidence；
 * 漏写 schema 会回落默认 ctds_auth 并按 fail-closed 全拒（runbook §3.1「部署陷阱」）。
 */
class RbacDeploymentWiringTest {

    private static final List<String> MYSQL_SERVICES = List.of(
            "space", "catalog", "contract", "delivery", "metering-billing", "evidence");

    @Test
    void mysqlDeploymentsInjectJdbcRbacSourceAndSchema() throws IOException {
        for (String service : MYSQL_SERVICES) {
            Path manifest = RbacThreeAdminMatrixTest.repoRoot()
                    .resolve("deploy/k8s/" + service + "-deployment.yaml");
            assertThat(Files.exists(manifest)).as("%s 部署清单须存在", service).isTrue();
            String yaml = Files.readString(manifest).replace("\r\n", "\n");
            assertThat(yaml).as("%s 须注入 CTDS_AUTH_RBAC_SOURCE=jdbc（成对）", service)
                    .contains("- name: CTDS_AUTH_RBAC_SOURCE\n              value: \"jdbc\"");
            assertThat(yaml).as("%s 须注入 CTDS_AUTH_RBAC_SCHEMA=ctds_evidence（成对）", service)
                    .contains("- name: CTDS_AUTH_RBAC_SCHEMA\n              value: \"ctds_evidence\"");
        }
    }
}
