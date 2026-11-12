package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.2 T1 / T2 / T7：三员权限点集合两两不重叠、admin 收权负向矩阵。
 * 依据 hifi §5、§9 E3 裁定 A 与 E5：Q1-A 后映射唯一权威来源 = 库表 seed（V3 迁移），
 * 默认 profile 的 yml 仅为无库兜底，不作判定对象。
 */
class RbacThreeAdminMatrixTest {

    private static final Pattern SEED = Pattern.compile("\\('([^']+)','([^']+)'");

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

    /** 解析 V3 初始映射 seed：同名角色跨服务视为同一平台角色（P4 口径）。 */
    private static Map<String, Set<String>> globalRolePoints() throws IOException {
        Path seed = repoRoot().resolve("services/evidence-service/src/main/resources/"
                + "db/migration/V3__create_rbac_tables.sql");
        assertThat(Files.exists(seed)).as("Q1-A：映射唯一权威来源 V3 seed 须存在").isTrue();
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Matcher matcher = SEED.matcher(Files.readString(seed));
        while (matcher.find()) {
            result.computeIfAbsent(matcher.group(1), key -> new LinkedHashSet<>())
                    .add(matcher.group(2));
        }
        return result;
    }

    static Path repoRoot() {
        return Path.of("").toAbsolutePath().getParent().getParent();
    }
}
