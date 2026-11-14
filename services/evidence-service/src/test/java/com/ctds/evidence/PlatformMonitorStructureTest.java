package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.4 T3 / T3b（B5-3 / B8-2，S1-9 / S2-8）：监控读数只读 + 无删除入口结构断言（扫描式）。
 * metrics / alerts 读面仅 GET（无任何指标写入 / 篡改 / 伪造入口）；全卡零 DELETE 映射
 * （监控 / 告警 / 留痕无删除路径，B8-R2 数据不可删除由审计载体结构性满足）；
 * `1013C0005` / `1013S0003` 落定登记（边界 8 义务）+ ExceptionHandler 503/400 精确映射。
 */
class PlatformMonitorStructureTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();
    private static final Path MAIN = ROOT.resolve(
            "services/evidence-service/src/main/java/com/ctds/evidence");

    private static String read(final Path base, final String... parts) throws IOException {
        return Files.readString(ROOT.resolve(base + "/" + String.join("/", parts))
                .normalize());
    }

    @Test
    void monitorReadFaceIsGetOnlyWithoutAnyWriteEntrance() throws IOException {
        final String controller = read(MAIN, "interfaces/PlatformMonitorController.java");
        assertThat(controller).contains("@RequirePermission(\"platform.monitor.read\")");
        assertThat(controller).contains("@RequirePermission(\"platform.alert.handle\")");
        assertThat(controller).contains("@GetMapping(path = \"/metrics\"");
        assertThat(controller).contains("@GetMapping(path = \"/alerts\"");
        assertThat(controller).contains("@PostMapping(path = \"/alerts/actions\"");
        assertThat(controller)
                .as("读面/处置不得含删除类映射（B8-2）")
                .doesNotContain("@DeleteMapping", "@PutMapping", "@PatchMapping");
    }

    @Test
    void prometheusClientIsReadAlertmanagerClientIsSilenceOnly() throws IOException {
        final String prometheus = read(MAIN,
                "infrastructure/monitoring/PrometheusClient.java");
        assertThat(prometheus)
                .as("Prometheus 客户端只读（无指标写入入口，B5-3）")
                .contains(".GET()")
                .doesNotContain(".POST(", ".PUT(", ".DELETE(");
        final String alertmanager = read(MAIN,
                "infrastructure/monitoring/AlertmanagerClient.java");
        assertThat(alertmanager)
                .as("Alertmanager 客户端仅消费 /api/v2/silences（处置静默唯一依赖，Q4-A）")
                .contains("/api/v2/silences")
                .doesNotContain("/api/v1/", "DELETE");
    }

    @Test
    void cardHasNoDeletePathForMonitoringAlertOrAuditData() throws IOException {
        for (final String file : List.of(
                "interfaces/PlatformMonitorController.java",
                "application/MonitorQueryService.java",
                "application/AlertQueryService.java",
                "application/AlertDisposeService.java",
                "infrastructure/monitoring/PrometheusClient.java",
                "infrastructure/monitoring/AlertmanagerClient.java")) {
            assertThat(read(MAIN, file))
                    .as("%s 不得含删除类 HTTP 映射（B8-2 无删除入口）", file)
                    .doesNotContain("@DeleteMapping");
        }
    }

    @Test
    void monitorErrorCodesRegisteredInReserved1013Segment() throws IOException {
        final String codes = read(MAIN, "domain/MonitorErrorCodes.java");
        assertThat(codes).as("Q8-A 顺延码值落定（边界 8 本卡登记）")
                .contains("1013C0005", "1013S0003")
                .doesNotContain("1011C", "1012C", "1013C0006", "1013S0004");
        final String handler = read(MAIN, "interfaces/EvidenceExceptionHandler.java");
        assertThat(handler).contains("case \"1013S0003\" -> HttpStatus.SERVICE_UNAVAILABLE");
        assertThat(handler).contains("case \"1013C0005\" -> HttpStatus.BAD_REQUEST");
    }

    @Test
    void disposeChannelIsUniqueEntranceWithClosedAlertSetAndAuditAction() throws IOException {
        final String service = read(MAIN, "application/AlertDisposeService.java");
        assertThat(service).as("B8-R1 处置三动作唯一入口 + 均留痕")
                .contains("AUDIT_ACTION_ALERT_DISPOSE = \"alert.dispose\"")
                .contains("\"ACKNOWLEDGE\"", "\"SILENCE\"", "\"ESCALATE\"");
        assertThat(service).as("处置对象封闭两规则（B7-R2 规则封闭）")
                .contains("\"CtdsBackendDown\"", "\"CtdsBackendHighErrorRate\"");
    }
}
