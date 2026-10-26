package com.ctds.connector.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 健康度阈值启动校验（hifi §9 T4 第 5 项 / §12）：1m~1440m 越界 fail-fast。
 * <p>直接构造 + 调校验方法（不启 Spring 上下文、不引新测试基建），覆盖下界 / 上界 / 合法值三分支。</p>
 */
class ConnectorPropertiesTest {

    @Test
    void defaultThresholdAccepted() {
        assertThatCode(() -> properties(Duration.ofMinutes(5)).validate()).doesNotThrowAnyException();
    }

    @Test
    void belowLowerBoundRejected() {
        assertThatThrownBy(() -> properties(Duration.ZERO).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1m~1440m");
    }

    @Test
    void aboveUpperBoundRejected() {
        assertThatThrownBy(() -> properties(Duration.ofMinutes(2000)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1m~1440m");
    }

    private static ConnectorProperties properties(final Duration offlineAfter) {
        final ConnectorProperties properties = new ConnectorProperties();
        properties.getHealth().setOfflineAfter(offlineAfter);
        return properties;
    }
}
