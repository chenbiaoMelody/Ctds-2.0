package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.3 T8/T9 结构与登记断言（无容器）：V4 授点与留痕行、读面只读 + 显式权限点、
 * 503 映射落位、1013 两新码登记（边界 8 义务）、全仓零 `tenant_id`/租户表/在线生命周期 API
 * （沿 RbacGovernanceContractTest 结构断言先例）。
 */
class DomainGovernanceContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();
    private static final Path SERVICE = ROOT.resolve("services/evidence-service");
    private static final Path MAIN = SERVICE.resolve("src/main/java/com/ctds/evidence");
    private static final Path COMMON_AUTH = ROOT.resolve("common/auth/src/main/java/com/ctds/common/auth");

    @Test
    void v4MigrationGrantsDomainReadWithChangeLogRow() throws IOException {
        Path migration = SERVICE.resolve("src/main/resources/db/migration/"
                + "V4__grant_platform_domain_read.sql");
        assertThat(Files.exists(migration)).as("V3 已应用不可改，授点须走 V4").isTrue();
        String sql = Files.readString(migration);
        assertThat(sql).contains("platform.sysadmin");
        assertThat(sql).contains("platform.domain.read");
        assertThat(sql.toUpperCase()).contains("ROLE_PERMISSION_CHANGE_LOG");
        assertThat(sql).contains("'0'", "'1'").as("留痕行 before/after 取值沿 JdbcRbacStore 口径");
    }

    @Test
    void domainReadFaceIsReadOnlyWithExplicitPermission() throws IOException {
        Path controller = MAIN.resolve("interfaces/DomainGovernanceController.java");
        assertThat(Files.exists(controller)).as("读面须落 evidence（Q1-A）").isTrue();
        String text = Files.readString(controller);
        assertThat(text).contains("@RequirePermission(\"platform.domain.read\")");
        assertThat(text).contains("@GetMapping");
        assertThat(text).doesNotContain("@PostMapping", "@PutMapping",
                "@DeleteMapping", "@PatchMapping").as("只读面零写端点（无在线生命周期 API，规格非目标 4）");
        assertThat(text).contains("/api/v1/platform");
    }

    @Test
    void platformRequestMappingOwnersAreExactlyTheTwoSanctionedControllers() throws IOException {
        Pattern mapping = Pattern.compile("@RequestMapping\\(\"/api/v1/platform");
        try (Stream<Path> files = Files.walk(SERVICE.resolve("src/main/java"))) {
            List<String> owners = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return mapping.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            // WBS-3.9.4 Q1-A（已确认设计）：平台读面第二持有者 = PlatformMonitorController
            //（监控读数 + 告警列表 + 处置唯一入口）；持有者集合封闭，防止第三个未登记控制器。
            assertThat(owners).as("/api/v1/platform 命名空间持有者 = 部署域读面 + 监控读面（封闭两件）")
                    .containsExactly("DomainGovernanceController.java", "PlatformMonitorController.java");
        }
    }

    @Test
    void exceptionHandlerMapsConfigMismatchTo503() throws IOException {
        String handler = Files.readString(MAIN.resolve("interfaces/EvidenceExceptionHandler.java"));
        assertThat(handler).contains("1013S0002");
        assertThat(handler).contains("SERVICE_UNAVAILABLE");
    }

    @Test
    void domainErrorCodesRegisteredInReserved1013Segment() throws IOException {
        Path codes = COMMON_AUTH.resolve("DomainErrorCodes.java");
        assertThat(Files.exists(codes)).as("1013 新码常量落 common/auth（过滤器写响应体所需）").isTrue();
        String text = Files.readString(codes);
        assertThat(text).contains("1013C0004");
        assertThat(text).contains("1013S0002");
        assertThat(text).contains("部署域已退役");
        assertThat(text).doesNotContain("1011C", "1012C").as("一域一段（ADR-005），不越段");
    }

    @Test
    void noTenantStructuresAnywhereInMainSources() throws IOException {
        Pattern tenant = Pattern.compile("tenant_id|tenantId|tenant_", Pattern.CASE_INSENSITIVE);
        Pattern tenantTable = Pattern.compile("CREATE TABLE[^(]*tenant", Pattern.CASE_INSENSITIVE);
        for (Path root : new Path[] {ROOT.resolve("services"), ROOT.resolve("common")}) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java")
                                || p.toString().endsWith(".sql")
                                || p.toString().endsWith(".yml")
                                || p.toString().endsWith(".yaml"))
                        .filter(p -> p.toString().replace('\\', '/').contains("/src/main/"))
                        .forEach(p -> {
                            try {
                                String text = Files.readString(p);
                                assertThat(tenant.matcher(text).find())
                                        .as("T8：%s 不得出现租户结构（无 tenant_id / 租户键）", p)
                                        .isFalse();
                                assertThat(tenantTable.matcher(text).find())
                                        .as("T8：%s 不得建租户表", p)
                                        .isFalse();
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        });
            }
        }
    }

    @Test
    void noOnlineLifecycleWriteEndpointsInAnyMainSource() throws IOException {
        Pattern writeMapping = Pattern.compile("@(Post|Put|Delete|Patch)Mapping",
                Pattern.CASE_INSENSITIVE);
        // 平台策略面 /api/v1/platform-policies（space，规格 B4）为既有合法路径，不属部署域生命周期；
        // 断言口径 = 部署域读面路径 /api/v1/platform/ 与租户路径 /api/v1/tenant 零写端点。
        // WBS-3.9.4 已确认设计（规格 B8-R1 处置通道唯一性）：PlatformMonitorController 的
        // POST /api/v1/platform/alerts/actions = 规格点名运维处置写端点（非部署域生命周期动作），
        // 单文件豁免；其余写端点禁令（含其文件内 Put/Delete/Patch）不变。
        Pattern sanctionedDispose = Pattern.compile("PlatformMonitorController\\.java$");
        Pattern lifecyclePath = Pattern.compile("/api/v1/platform/|/api/v1/platform\"|/api/v1/tenant",
                Pattern.CASE_INSENSITIVE);
        for (Path root : new Path[] {ROOT.resolve("services"), ROOT.resolve("common")}) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> p.toString().replace('\\', '/').contains("/src/main/"))
                        .forEach(p -> {
                            if (sanctionedDispose.matcher(p.toString()).find()) {
                                return;
                            }
                            try {
                                String text = Files.readString(p);
                                assertThat(writeMapping.matcher(text).find()
                                                && lifecyclePath.matcher(text).find())
                                        .as("T8：%s 不得同时含写端点注解与部署域/租户生命周期路径"
                                                + "（在线生命周期 API 非目标，规格 :178；"
                                                + "/api/v1/platform-policies 为既有策略面不在断言内）", p)
                                        .isFalse();
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        });
            }
        }
    }
}
