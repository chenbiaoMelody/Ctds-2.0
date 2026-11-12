package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.2 T1 / T2 / T7：三员权限点集合两两不重叠、admin 收权负向矩阵。
 * 依据 hifi §5 与 §9 E3 裁定 A；断言对象 = 11 服务 application.yml 的登记面（默认 profile 映射来源）。
 */
class RbacThreeAdminMatrixTest {

    private static final Set<String> THREE_ADMINS = Set.of(
            "platform.sysadmin", "platform.secadmin", "platform.audadmin");

    /** 三类关键点（规格 B2-R4）：业务配置 / 权限授予 / 审计读面，全部取自既有登记点，零新增。 */
    private static final Set<String> BUSINESS_CONFIG = Set.of(
            "catalog.governance", "contract.template.manage", "delivery.governance",
            "metering.governance", "billing.governance", "sandbox.review");
    private static final Set<String> GRANT = Set.of("connector.review");
    private static final Set<String> AUDIT_READ = Set.of("evidence.governance");

    /** E3 裁定 A：admin 落地时一次性撤除的两面（含相关写点与密钥执行权）。 */
    private static final Set<String> WITHDRAWN_FROM_ADMIN = Set.of(
            "evidence.governance", "connector.review", "connector.upgrade.confirm",
            "connector.cancel", "kms.admin");

    @Test
    void threeAdminRolesAreRegistered() throws IOException {
        assertThat(globalRolePoints().keySet()).containsAll(THREE_ADMINS);
    }

    @Test
    void threeAdminKeyPointClassesDoNotOverlap() throws IOException {
        Map<String, Set<String>> all = globalRolePoints();
        for (String role : THREE_ADMINS) {
            Set<String> points = all.getOrDefault(role, Set.of());
            int classes = 0;
            if (intersects(points, BUSINESS_CONFIG)) {
                classes++;
            }
            if (intersects(points, GRANT)) {
                classes++;
            }
            if (intersects(points, AUDIT_READ)) {
                classes++;
            }
            assertThat(classes).as("%s 不得跨两类以上关键点", role).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void adminWithdrawsAuditReadAndGrantPointsButKeepsGovernanceApproval() throws IOException {
        Set<String> admin = globalRolePoints().getOrDefault("admin", Set.of());
        assertThat(admin).doesNotContainAnyElementsOf(WITHDRAWN_FROM_ADMIN);
        assertThat(admin).contains("catalog.governance");
    }

    @Test
    void kmsAdminNeverSharesRoleWithGrantPoint() throws IOException {
        for (Map.Entry<String, Set<String>> entry : globalRolePoints().entrySet()) {
            assertThat(entry.getValue().contains("kms.admin") && intersects(entry.getValue(), GRANT))
                    .as("%s 不得同持 kms.admin 与权限授予", entry.getKey()).isFalse();
        }
    }

    @Test
    void connectorHealthReadIsRegistered() throws IOException {
        Set<String> points = new LinkedHashSet<>();
        globalRolePoints().values().forEach(points::addAll);
        assertThat(points).contains("connector.health.read");
    }

    private static boolean intersects(final Set<String> left, final Set<String> right) {
        return left.stream().anyMatch(right::contains);
    }

    /** P4：全局同名 admin 跨 9 服务同持，按角色名合并后判定。 */
    private static Map<String, Set<String>> globalRolePoints() throws IOException {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (Stream<Path> dirs = Files.list(repoRoot().resolve("services"))) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path yml = dir.resolve("src/main/resources/application.yml");
                if (!Files.exists(yml)) {
                    continue;
                }
                parsePermissions(yml).forEach((role, points) ->
                        result.computeIfAbsent(role, key -> new LinkedHashSet<>()).addAll(points));
            }
        }
        return result;
    }

    private static Map<String, Set<String>> parsePermissions(final Path yml) throws IOException {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        boolean inside = false;
        int base = -1;
        for (String line : Files.readAllLines(yml)) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (text.startsWith("permissions:")) {
                inside = true;
                base = indent;
                continue;
            }
            if (!inside || indent <= base) {
                if (inside) {
                    break;
                }
                continue;
            }
            int colon = text.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            Set<String> points = new LinkedHashSet<>();
            for (String value : text.substring(colon + 1).split(",")) {
                if (!value.isBlank()) {
                    points.add(value.trim());
                }
            }
            result.put(text.substring(0, colon).trim(), points);
        }
        return result;
    }

    static Path repoRoot() {
        return Path.of("").toAbsolutePath().getParent().getParent();
    }
}
