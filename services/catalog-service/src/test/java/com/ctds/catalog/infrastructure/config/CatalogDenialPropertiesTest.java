package com.ctds.catalog.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CatalogDenialPropertiesTest {

    @Test
    void rejectsNonPositiveValues() {
        final CatalogDenialProperties properties = new CatalogDenialProperties();
        assertThat(properties.getMaxRequestsPerMinute()).isEqualTo(20);
        assertThat(properties.getMaxTrackedKeys()).isEqualTo(10_000);
        assertThat(properties.getMaxSubjectRequestsPerMinute()).isEqualTo(100);
        assertThat(properties.getMaxSubjectTrackedKeys()).isEqualTo(10_000);
        assertThatThrownBy(() -> properties.setMaxRequestsPerMinute(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxRequestsPerMinute(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxRequestsPerMinute(Integer.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxTrackedKeys(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxTrackedKeys(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxTrackedKeys(Integer.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxSubjectRequestsPerMinute(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxSubjectRequestsPerMinute(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxSubjectRequestsPerMinute(Integer.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxSubjectTrackedKeys(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxSubjectTrackedKeys(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxSubjectTrackedKeys(Integer.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
