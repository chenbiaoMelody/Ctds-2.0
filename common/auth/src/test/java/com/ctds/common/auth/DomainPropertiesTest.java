package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * WBS-3.9.3 T1/T4/T5 支撑：`ctds.domain.*` 配置绑定与状态值域（hifi §3 / §4 边界 1/5）。
 * 缺省 ACTIVE 零行为；仅 ACTIVE/RETIRED 合法，非法值不静默降级；豁免模式缺省 [/actuator/**]。
 */
class DomainPropertiesTest {

    @Test
    void defaultsAreActiveWithActuatorExemption() {
        DomainProperties properties = new DomainProperties();
        assertThat(properties.getStatus()).isEqualTo("ACTIVE");
        assertThat(properties.isRetired()).isFalse();
        assertThat(properties.getRetired().getPermitPathPatterns())
                .containsExactly("/actuator/**");
        assertThat(properties.getSchemaList()).isEmpty();
        assertThat(properties.getServiceList()).isEmpty();
    }

    @Test
    void relaxedBindingMapsCommaSeparatedLists() {
        Map<String, Object> source = new HashMap<>();
        source.put("ctds.domain.id", "ctds-demo-domain");
        source.put("ctds.domain.status", "RETIRED");
        source.put("ctds.domain.schema-list", "ctds_evidence,ctds_subject");
        source.put("ctds.domain.service-list", "evidence-service,subject-service");
        source.put("ctds.domain.retired.permit-path-patterns",
                "/actuator/**,/api/v1/evidence/audit/**");
        DomainProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("ctds.domain", Bindable.of(DomainProperties.class)).get();
        assertThat(properties.getId()).isEqualTo("ctds-demo-domain");
        assertThat(properties.getStatus()).isEqualTo("RETIRED");
        assertThat(properties.isRetired()).isTrue();
        assertThat(properties.getSchemaList()).containsExactly("ctds_evidence", "ctds_subject");
        assertThat(properties.getServiceList()).containsExactly("evidence-service", "subject-service");
        assertThat(properties.getRetired().getPermitPathPatterns())
                .containsExactly("/actuator/**", "/api/v1/evidence/audit/**");
    }

    @Test
    void illegalStatusIsRejectedNotSilentlyActive() {
        DomainProperties properties = new DomainProperties();
        properties.setStatus("PAUSED");
        assertThatThrownBy(properties::validateStatus)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAUSED");
    }

    @Test
    void legalStatusValuesPassValidation() {
        DomainProperties active = new DomainProperties();
        active.validateStatus();
        active.setStatus("ACTIVE");
        active.validateStatus();
        active.setStatus("RETIRED");
        active.validateStatus();
        assertThat(active.isRetired()).isTrue();
    }

    @Test
    void lifecycleRegistrationIsAllOrNothing() {
        DomainProperties blank = new DomainProperties();
        assertThat(blank.hasCompleteLifecycleRegistration()).isTrue();

        DomainProperties complete = new DomainProperties();
        complete.setAction("RETIRE");
        complete.setChangeOrder("CHG-2026-001");
        complete.setOperator("ops-a");
        assertThat(complete.hasCompleteLifecycleRegistration()).isTrue();

        DomainProperties partial = new DomainProperties();
        partial.setAction("RETIRE");
        partial.setChangeOrder("CHG-2026-001");
        assertThat(partial.hasCompleteLifecycleRegistration()).isFalse();
    }
}
