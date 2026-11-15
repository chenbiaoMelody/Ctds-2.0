package com.ctds.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.4 T1（B5-1 / AC B5-1，S1-2）：ADR-014 指标接入暴露面收敛结构断言（扫描式）。
 * 12 服务（11 业务服务 + gateway，Q2-A）逐服务：pom 两依赖（actuator + micrometer-registry-prometheus）
 * + exposure 精确值 `health,prometheus`（无其他端点开放）。example 为唯一样板（WBS 2.5.3 交付，
 * 本卡零改动）——本测试同时守卫样板不被回退。
 */
class MetricExposureStructureTest {

    /** 12 服务相对 services/ 的目录名（Q2-A：11 业务服务 + gateway；example = 样板）。 */
    private static final List<String> SERVICES = List.of(
            "example-service", "space-service", "catalog-service", "contract-service",
            "delivery-service", "connector-service", "metering-billing", "evidence-service",
            "gateway-service", "subject-service", "did", "kms");

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();

    private static Path serviceDir(final String service) {
        return ROOT.resolve("services").resolve(service);
    }

    @Test
    void everyServiceCarriesActuatorAndPrometheusRegistryDependencies() throws IOException {
        for (String service : SERVICES) {
            String pom = Files.readString(serviceDir(service).resolve("pom.xml"));
            assertThat(pom).as("%s pom 须含 spring-boot-starter-actuator（ADR-014 :34/:36）", service)
                    .contains("spring-boot-starter-actuator");
            assertThat(pom).as("%s pom 须含 micrometer-registry-prometheus（ADR-014 :34/:36）", service)
                    .contains("micrometer-registry-prometheus");
        }
    }

    @Test
    void everyServiceExposesExactlyHealthAndPrometheus() throws IOException {
        for (String service : SERVICES) {
            Path yml = serviceDir(service).resolve("src/main/resources/application.yml");
            assertThat(Files.exists(yml)).as("%s application.yml 须存在", service).isTrue();
            String text = Files.readString(yml);
            assertThat(text).as("%s 须含 management.endpoints.web.exposure.include（ADR-014 :34）", service)
                    .contains("management:");
            assertThat(text).as("%s 暴露面须精确为 health,prometheus（B5-1，评审④ P2 行尾锚防后缀追加）", service)
                    .containsPattern("(?s)management:.*exposure:\\s*\\n\\s*include: health,prometheus(\\s|$)");
            assertThat(text).as("%s 不得开放 prometheus/health 之外的端点（B5-1 暴露面收敛）", service)
                    .doesNotContain("include: health,prometheus,")
                    .doesNotContain("include: health,prometheus,*")
                    .doesNotContain("include: \"*\"")
                    .doesNotContain("include: '*'");
        }
    }

    @Test
    void exampleTemplateStaysUntouchedAsSample() throws IOException {
        String pom = Files.readString(serviceDir("example-service").resolve("pom.xml"));
        assertThat(pom).contains("spring-boot-starter-actuator");
        assertThat(pom).contains("micrometer-registry-prometheus");
    }

    /** 评审①P1 防复发守卫：k8s 七清单 YAML 可解析 + 三注解齐备（结构损坏在测试期暴露，不进部署期）。 */
    @Test
    void k8sDeploymentManifestsParseAndCarryScrapeAnnotations() throws IOException {
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        for (String service : List.of("space", "catalog", "contract", "delivery",
                "metering-billing", "evidence", "gateway")) {
            Path manifest = ROOT.resolve("deploy/k8s").resolve(service + "-deployment.yaml");
            Object parsed = yaml.load(Files.readString(manifest));
            assertThat(parsed).as("%s 清单须为可解析 YAML（Map 结构）", manifest.getFileName()).isInstanceOf(java.util.Map.class);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> template = (java.util.Map<String, Object>)
                    ((java.util.Map<String, Object>) ((java.util.Map<String, Object>) ((java.util.Map<String, Object>)
                            parsed).get("spec")).get("template")).get("metadata");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> annotations = (java.util.Map<String, Object>) template.get("annotations");
            assertThat(annotations).as("%s Pod 模板须含三注解（ADR-014 :36）", manifest.getFileName())
                    .containsEntry("prometheus.io/scrape", "true")
                    .containsKey("prometheus.io/port")
                    .containsEntry("prometheus.io/path", "/actuator/prometheus");
        }
    }
}
