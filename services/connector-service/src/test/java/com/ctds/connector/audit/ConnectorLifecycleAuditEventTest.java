package com.ctds.connector.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.connector.domain.ConnectorActionLog;
import com.ctds.connector.domain.ConnectorLogRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 生命周期 JSONL 审计埋点（hifi §9 T6）：以捕获式 {@link AuditRecorder} 断言 6 类新动作的事件要素
 * （动作 / 对象 / 结论 / 摘要可读），沿 3.6.3 T8 形态，不依赖异步文件设施。
 *
 * <p>表侧"各落 1 行"由集成用例 {@code ConnectorLifecycleAuditTest} 断言；两侧同源于
 * {@link ConnectorAuditService#record}（先写表、再写审计事件，无分支差异），故此处只验事件映射，
 * 不重复驱动应用服务。</p>
 */
class ConnectorLifecycleAuditEventTest {

    private static final String OWNER = "S20261026000111";

    private final CapturingRepository repository = new CapturingRepository();
    private final List<AuditEvent> events = new ArrayList<>();

    @Test
    void sixLifecycleActionsProduceAuditEvents() {
        final Map<String, String> lifecycleActions = new LinkedHashMap<>();
        lifecycleActions.put(ConnectorAuditActions.UPGRADE_REQUESTED, "CN000001");
        lifecycleActions.put(ConnectorAuditActions.UPGRADE_APPLIED, "CN000001");
        lifecycleActions.put(ConnectorAuditActions.UPGRADE_CONFIRMED, "CN000001");
        lifecycleActions.put(ConnectorAuditActions.CANCELLED, "CN000001");
        lifecycleActions.put(ConnectorAuditActions.OCCUPANCY_REGISTER, "CN000001");
        lifecycleActions.put(ConnectorAuditActions.OCCUPANCY_RELEASE, "CN000001");

        final ConnectorAuditService service = service();
        lifecycleActions.forEach((action, connectorNo) -> service.success(action, "CA000001", connectorNo,
                OWNER, "from=1.0.0;to=1.1.0;compatibility=COMPATIBLE"));

        assertThat(events).hasSize(lifecycleActions.size());
        assertThat(events).extracting(AuditEvent::action)
                .containsExactlyElementsOf(lifecycleActions.keySet());
        events.forEach(event -> {
            assertThat(event.actor()).isEqualTo(OWNER);
            assertThat(event.targetType()).isEqualTo("connector");
            assertThat(event.targetId()).isEqualTo("CN000001");
            assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(event.detail()).containsEntry("applicationNo", "CA000001")
                    .containsEntry("detail", "from=1.0.0;to=1.1.0;compatibility=COMPATIBLE");
        });
    }

    @Test
    void withdrawalEventIsDistinguishableFromCancellation() {
        final ConnectorAuditService service = service();

        service.success(ConnectorAuditActions.CANCELLED, "CA000002", null, OWNER,
                "fromStatus=PENDING_REVIEW;withdraw=true");
        service.success(ConnectorAuditActions.CANCELLED, "CA000003", "CN000002", OWNER,
                "fromStatus=REGISTERED;inProgress=0");

        assertThat(events).hasSize(2);
        assertThat(events.get(0).action()).isEqualTo(events.get(1).action());
        assertThat(events.get(0).targetId()).isEqualTo("CA000002");
        assertThat(events.get(0).detail()).containsEntry("detail", "fromStatus=PENDING_REVIEW;withdraw=true");
        assertThat(events.get(1).targetId()).isEqualTo("CN000002");
        assertThat(events.get(1).detail()).containsEntry("detail", "fromStatus=REGISTERED;inProgress=0");
    }

    private ConnectorAuditService service() {
        final StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("auditRecorder", recorder());
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
