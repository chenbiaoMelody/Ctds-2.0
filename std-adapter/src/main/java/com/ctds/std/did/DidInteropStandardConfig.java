package com.ctds.std.did;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DID 互认域装配（WBS-3.1.10 hifi §5 + ADR-008 §3.4 替换规则）：由使用方 {@code @Import} 显式引入
 * （只对需要的服务生效，不向所有引入 std-adapter 的服务注入无用 Bean）。
 * <p>收口意义（hifi §7 T11）：本域真实实现只在本模块被引用，业务服务仅依赖 {@link DidInteropStandardApi}
 * 接口类型；{@link LocalDidStatusPort} 由使用方提供，缺省 = 出向验证返回不可用（无本空间身份能力的使用方）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class DidInteropStandardConfig {

    @Bean
    @ConditionalOnMissingBean(DidInteropStandardApi.class)
    public DidInteropStandardApi didInteropStandardApi(final ObjectProvider<LocalDidStatusPort> localStatusPort) {
        return new MockDidInteropStandardApi(DidInteropSamples.fromClasspath(), localStatusPort.getIfAvailable());
    }
}
