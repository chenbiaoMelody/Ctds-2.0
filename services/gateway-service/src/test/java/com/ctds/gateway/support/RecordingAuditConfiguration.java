package com.ctds.gateway.support;

import com.ctds.common.logging.AuditRecorder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 网关测试共用审计装配：以内存录制替身替代真实异步文件审计器（{@code ctds.audit.enabled=false}），
 * 供"放行 ≡ 计量 1 条 / 拒绝 0 条"与事件字段断言核对。
 *
 * <p>集中一处（各测试类 {@code @Import}）：避免各测试类各自声明同名 {@code @TestConfiguration}
 * 造成 BeanDefinition 覆盖冲突；同一配置类被扫描与显式导入时按类去重。</p>
 */
@TestConfiguration
public class RecordingAuditConfiguration {

    @Bean
    @Primary
    public AuditRecorder recordingAuditRecorder() {
        return new RecordingAuditRecorder();
    }
}
