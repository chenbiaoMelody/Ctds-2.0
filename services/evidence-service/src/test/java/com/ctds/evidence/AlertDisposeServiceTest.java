package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.evidence.application.AlertDisposeService;
import com.ctds.evidence.application.MonitorProperties;
import com.ctds.evidence.application.MonitorViews.DisposeRequest;
import com.ctds.evidence.application.MonitorViews.DisposeView;
import com.ctds.evidence.infrastructure.monitoring.AlertmanagerClient;
import com.ctds.evidence.infrastructure.monitoring.PrometheusClient;
import com.ctds.evidence.support.MonitorStubServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.4 T10 审计腿 + 边界（hifi §4 #1/#8/#9；沿 3.9.3 DomainLifecycleAuditEmitterTest
 * CapturingRecorder 范式）：处置留痕四要素断言（处置人 = actor / 时点 = 审计载体自带 /
 * 对象 = targetId / 结论 = detail(action·note·静默时长·silenceId)，outcome=SUCCESS 枚举零改动）；
 * 数据源未配置（base-url 空）→ `1013S0003` fail-closed（不伪造读数）；
 * 留痕载体缺位（AuditRecorder 不可用）→ 处置照常执行 + 不阻断（WARN 可观测）。
 */
class AlertDisposeServiceTest {

    private static final Instant FIXED = Instant.parse("2026-11-14T15:31:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MonitorStubServer STUB = createStub();

    private static MonitorStubServer createStub() {
        try {
            return new MonitorStubServer();
        } catch (final IOException failure) {
            throw new IllegalStateException("monitor stub start failed", failure);
        }
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    private static final class CapturingRecorder implements AuditRecorder {
        final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(final AuditEvent event) {
            events.add(event);
        }
    }

    private static AlertDisposeService service(final AuditRecorder recorder) {
        return new AlertDisposeService(new AlertmanagerClient(STUB.baseUrl(), MAPPER),
                new MonitorProperties(),
                Clock.fixed(FIXED, Clock.systemDefaultZone().getZone()),
                new FixedAuditRecorderProvider(recorder));
    }

    /** 审计器供给桩（ObjectProvider 多抽象方法，非函数接口——匿名实现沿 AuthAutoConfiguration 口径）。 */
    private static final class FixedAuditRecorderProvider
            implements org.springframework.beans.factory.ObjectProvider<AuditRecorder> {

        private final AuditRecorder recorder;

        FixedAuditRecorderProvider(final AuditRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public AuditRecorder getObject() {
            return recorder;
        }

        @Override
        public AuditRecorder getIfAvailable() {
            return recorder;
        }
    }

    @Test
    void acknowledgeDisposesAndRecordsFourElementAuditEvent() {
        final CapturingRecorder recorder = new CapturingRecorder();
        final DisposeView view = service(recorder).dispose("S-admin-dispose",
                new DisposeRequest("CtdsBackendDown", "ACKNOWLEDGE", "已知悉，处置中", null));
        assertThat(view.result()).isEqualTo("OK");
        assertThat(view.silenceId()).isNull();
        assertThat(view.recordedAt()).isEqualTo("2026-11-14T15:31:00Z");
        assertThat(recorder.events).hasSize(1);
        final AuditEvent event = recorder.events.get(0);
        assertThat(event.action()).isEqualTo("alert.dispose");
        assertThat(event.actor()).as("四要素·处置人").isEqualTo("S-admin-dispose");
        assertThat(event.targetId()).as("四要素·对象").isEqualTo("CtdsBackendDown");
        assertThat(event.outcome()).as("枚举零改动（沿 E-2 先例）").isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.detail())
                .as("四要素·结论（动作 + 处置说明）")
                .containsEntry("action", "ACKNOWLEDGE")
                .containsEntry("note", "已知悉，处置中");
    }

    @Test
    void silenceAuditCarriesDurationAndSilenceId() {
        final CapturingRecorder recorder = new CapturingRecorder();
        service(recorder).dispose("S-admin-dispose",
                new DisposeRequest("CtdsBackendHighErrorRate", "SILENCE", "变更窗口", 60));
        final AuditEvent event = recorder.events.get(0);
        assertThat(event.detail())
                .as("静默留痕 detail 含动作/结论/静默时长/silenceId（键数预算 ≤20）")
                .containsEntry("action", "SILENCE")
                .containsEntry("note", "变更窗口")
                .containsEntry("durationMinutes", "60")
                .containsEntry("silenceId", MonitorStubServer.SILENCE_ID);
    }

    @Test
    void missingAuditRecorderDoesNotBlockDispose() {
        final DisposeView view = service(null).dispose("S-admin-dispose",
                new DisposeRequest("CtdsBackendDown", "ESCALATE", null, null));
        assertThat(view.result()).as("留痕载体缺位不阻断处置（沿 3.9.3 E-7 口径）").isEqualTo("OK");
    }

    @Test
    void unknownAlertAndUnknownActionAndOutOfRangeDurationAreRejected() {
        final AlertDisposeService sut = service(new CapturingRecorder());
        assertThatThrownBy(() -> sut.dispose("S-admin-dispose",
                        new DisposeRequest("Nope", "ACKNOWLEDGE", null, null)))
                .isInstanceOf(BizException.class)
                .as("处置对象非封闭两规则名 → 1013C0005")
                .extracting(ex -> ((BizException) ex).getErrorCode().value())
                .isEqualTo("1013C0005");
        assertThatThrownBy(() -> sut.dispose("S-admin-dispose",
                        new DisposeRequest("CtdsBackendDown", "DESTROY", null, null)))
                .isInstanceOf(BizException.class)
                .as("未知动作 → 1013C0001（参数越界复用，不新开码）")
                .extracting(ex -> ((BizException) ex).getErrorCode().value())
                .isEqualTo("1013C0001");
        assertThatThrownBy(() -> sut.dispose("S-admin-dispose",
                        new DisposeRequest("CtdsBackendDown", "SILENCE", null, 2000)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode().value())
                .isEqualTo("1013C0001");
    }

    @Test
    void blankPrometheusBaseUrlFailsClosedWithoutFabricatedData() {
        final PrometheusClient client = new PrometheusClient("", MAPPER);
        assertThatThrownBy(() -> client.queryScalar("up{job=\"ctds-backend\"}"))
                .isInstanceOf(BizException.class)
                .as("base-url 未配置 = 数据源不可用（fail-closed，不伪造空数据/零值）")
                .extracting(ex -> ((BizException) ex).getErrorCode().value())
                .isEqualTo("1013S0003");
    }

    @Test
    void blankSubjectFallsBackToAnonymousActor() {
        final CapturingRecorder recorder = new CapturingRecorder();
        service(recorder).dispose(" ", new DisposeRequest("CtdsBackendDown", "ACKNOWLEDGE", null, null));
        assertThat(recorder.events.get(0).actor()).isEqualTo("anonymous");
    }
}
