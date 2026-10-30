package com.ctds.connector.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.connector.domain.ConnectorActionLog;
import com.ctds.connector.domain.ConnectorLogRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * JSONL 审计埋点（hifi §9 T8）：以捕获式 {@link AuditRecorder} 断言事件要素（谁 / 动作 / 对象 / 结论 / 摘要），
 * 不依赖异步文件设施（文件落盘由 common-logging 自身用例覆盖），覆盖"有审计器 / 无审计器"两分支。
 */
class ConnectorAuditServiceTest {

    private final CapturingRepository repository = new CapturingRepository();
    private final List<AuditEvent> events = new ArrayList<>();

    @Test
    void successWritesRowAndAuditEvent() {
        service(recorder()).success(ConnectorAuditActions.APPLY, "CA000001", null, "S1", "name=conn-a");

        assertThat(repository.rows).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo(ConnectorAuditActions.APPLY);
            assertThat(row.result()).isEqualTo("SUCCESS");
            assertThat(row.operatorNo()).isEqualTo("S1");
        });
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.actor()).isEqualTo("S1");
            assertThat(event.action()).isEqualTo(ConnectorAuditActions.APPLY);
            assertThat(event.targetType()).isEqualTo("connector");
            assertThat(event.targetId()).isEqualTo("CA000001");
            assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(event.detail()).containsEntry("applicationNo", "CA000001")
                    .containsEntry("detail", "name=conn-a");
        });
    }

    @Test
    void deniedEventCarriesReasonCodeAndDeniedOutcome() {
        service(recorder()).denied(ConnectorAuditActions.DENIED, "CA000002", "CN000001", "S2",
                "1010C0006", "admission=REJECTED");

        assertThat(repository.rows).singleElement().satisfies(row -> {
            assertThat(row.result()).isEqualTo("DENIED");
            assertThat(row.reasonCode()).isEqualTo("1010C0006");
        });
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED);
            assertThat(event.targetId()).isEqualTo("CN000001");
            assertThat(event.detail()).containsEntry("reasonCode", "1010C0006");
        });
    }

    @Test
    void auditEventSkippedWhenRecorderAbsent() {
        service(null).success(ConnectorAuditActions.HEARTBEAT, "CA000003", "CN000001", "S3", null);

        assertThat(repository.rows).hasSize(1);
        assertThat(events).isEmpty();
    }

    private ConnectorAuditService service(final AuditRecorder recorder) {
        final StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (recorder != null) {
            factory.addBean("auditRecorder", recorder);
        }
        final ObjectProvider<AuditRecorder> provider = factory.getBeanProvider(AuditRecorder.class);
        return new ConnectorAuditService(repository, provider);
    }

    private AuditRecorder recorder() {
        return events::add;
    }

    /** 留痕仓储替身（只收集插入行；其余读面无调用点）。 */
    private static final class CapturingRepository implements ConnectorLogRepository {

        private final List<ConnectorActionLog> rows = new ArrayList<>();

        @Override
        public void insert(final ConnectorActionLog log) {
            rows.add(log);
        }

        @Override
        public void attachConnectorNo(final String applicationNo, final String connectorNo) {
            // 核验通过回填，本用例无调用点
        }

        @Override
        public List<ConnectorActionLog> findByConnectorNo(final String connectorNo, final long offset,
                final int limit) {
            return List.of();
        }

        @Override
        public long countByConnectorNo(final String connectorNo) {
            return rows.size();
        }

        @Override
        public java.util.Optional<com.ctds.connector.domain.ConnectorActionLog>
                findLatestByDetailPrefix(final String connectorNo, final String action,
                        final String detailPrefix) {
            return java.util.Optional.empty();
        }
    }
}
