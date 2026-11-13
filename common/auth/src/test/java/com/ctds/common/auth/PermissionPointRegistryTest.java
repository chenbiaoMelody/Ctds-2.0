package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.2 T5：主源码 @RequirePermission 值与登记清单逐项相等，零未登记权限点。
 * 依据 hifi §5 T5 / §9 E2：比对集合 = 主源注解值 ∪ 既有登记点（库表 seed 承载），
 * 测试桩排除；新增权限点仅 connector.health.read（规格边界 8）。
 */
class PermissionPointRegistryTest {

    private static final Pattern ANNOTATION = Pattern.compile("@RequirePermission\\(\"([^\"]+)\"\\)");
    private static final Pattern SEED = Pattern.compile("\\('([^']+)','([^']+)'");
    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();

    @Test
    void everyMainSourceAnnotationValueIsRegistered() throws IOException {
        // 常量引用（OwnershipGuard.REVIEW_PERMISSION）由 seed 登记承载，不在此手工补白，
        // 否则 seed 丢点仍会假绿（评审④ P3-4①）。
        Set<String> registered = new LinkedHashSet<>(registeredPoints());
        assertThat(registered).containsAll(annotationValues());
    }

    @Test
    void newPointIsRegisteredAndUsedByHealthOverview() throws IOException {
        assertThat(registeredPoints()).contains("connector.health.read");
        assertThat(annotationValues()).contains("connector.health.read");
    }

    private static Set<String> annotationValues() throws IOException {
        Set<String> values = new LinkedHashSet<>();
        try (Stream<Path> dirs = Files.list(ROOT.resolve("services"))) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path main = dir.resolve("src/main/java");
                if (!Files.exists(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        Matcher matcher = ANNOTATION.matcher(Files.readString(file));
                        while (matcher.find()) {
                            values.add(matcher.group(1));
                        }
                    }
                }
            }
        }
        return values;
    }

    /** 登记清单 = V3 初始映射 seed（Q1-A 后唯一权威来源）。 */
    private static Set<String> registeredPoints() throws IOException {
        Path seed = ROOT.resolve("services/evidence-service/src/main/resources/"
                + "db/migration/V3__create_rbac_tables.sql");
        Set<String> points = new LinkedHashSet<>();
        Matcher matcher = SEED.matcher(Files.readString(seed));
        while (matcher.find()) {
            points.add(matcher.group(2));
        }
        return points;
    }
}
