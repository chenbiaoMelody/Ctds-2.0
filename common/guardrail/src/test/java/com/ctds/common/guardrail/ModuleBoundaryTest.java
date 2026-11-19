package com.ctds.common.guardrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {

    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "import java.net.", "import org.springframework.", "import okhttp3.",
            "import org.apache.hc.", "import com.squareup.okhttp");

    @Test
    @DisplayName("T11 模块边界：主源零网络/Spring 导入")
    void mainSourcesShouldHaveNoNetworkOrSpringImports() throws IOException {
        final Path sources = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(sources), "main sources must exist: " + sources);
        final List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sources)) {
            final List<Path> files = walk.filter(path -> path.toString().endsWith(".java")).toList();
            for (final Path file : files) {
                for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    final String trimmed = line.trim();
                    for (final String forbidden : FORBIDDEN_IMPORTS) {
                        if (trimmed.startsWith(forbidden)) {
                            hits.add(file + ": " + trimmed);
                        }
                    }
                    if (trimmed.startsWith("import ") && trimmed.contains("HttpClient")) {
                        hits.add(file + ": " + trimmed);
                    }
                }
            }
        }
        assertEquals(List.of(), hits);
    }

    @Test
    @DisplayName("T11 模块边界：生产依赖仅 common-model-gateway，测试依赖隔离")
    void pomShouldHaveSingleProductionDependency() throws IOException {
        final String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(pom.contains("<artifactId>common-model-gateway</artifactId>"));

        final List<String> productionArtifacts = new ArrayList<>();
        final Matcher matcher = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pom);
        while (matcher.find()) {
            final String block = matcher.group(1);
            if (!block.contains("<scope>test</scope>")) {
                final Matcher artifact = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(block);
                if (artifact.find()) {
                    productionArtifacts.add(artifact.group(1));
                }
            }
        }
        assertEquals(List.of("common-model-gateway"), productionArtifacts);
    }
}