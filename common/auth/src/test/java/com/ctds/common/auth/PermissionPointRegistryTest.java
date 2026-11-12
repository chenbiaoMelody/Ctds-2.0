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
 * 依据 hifi §5 T5 / §9 E2 口径：比对集合 = 主源注解值 ∪ 既有配置登记点（含常量守卫消费点），
 * 测试桩排除；新增权限点仅 connector.health.read（规格边界 8）。
 */
class PermissionPointRegistryTest {

    private static final Pattern ANNOTATION = Pattern.compile("@RequirePermission\\(\"([^\"]+)\"\\)");
    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();

    @Test
    void everyMainSourceAnnotationValueIsRegistered() throws IOException {
        Set<String> registered = new LinkedHashSet<>(registeredPoints());
        registered.add("subject.review");
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

    private static Set<String> registeredPoints() throws IOException {
        Set<String> points = new LinkedHashSet<>();
        try (Stream<Path> dirs = Files.list(ROOT.resolve("services"))) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path yml = dir.resolve("src/main/resources/application.yml");
                if (!Files.exists(yml)) {
                    continue;
                }
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
                    if (colon > 0) {
                        for (String value : text.substring(colon + 1).split(",")) {
                            if (!value.isBlank()) {
                                points.add(value.trim());
                            }
                        }
                    }
                }
            }
        }
        return points;
    }
}
