package com.ctds.did.infrastructure;

import com.ctds.did.application.DidResolutionService;
import com.ctds.std.did.DidInteropStandardConfig;
import com.ctds.std.did.LocalDidStatusPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 互认能力装配（WBS-3.1.10 hifi §5）：注册本空间 DID 状态端口实现（进程内委托既有解析能力），
 * 并经 {@code @Import} 引入 std-adapter 的互认域装配——协议实现唯一落点在 std-adapter，
 * 本服务源集不出现具体实现类名（收口守卫见 {@code architecture.InteropSeamTest}）。
 */
@Configuration(proxyBeanMethods = false)
@Import(DidInteropStandardConfig.class)
public class DidInteropConfig {

    @Bean
    public LocalDidStatusPort localDidStatusPort(final DidResolutionService resolutionService) {
        return new LocalDidStatusAdapter(resolutionService);
    }
}
