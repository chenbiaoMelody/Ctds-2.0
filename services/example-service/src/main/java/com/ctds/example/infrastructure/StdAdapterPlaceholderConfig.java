package com.ctds.example.infrastructure;

import com.ctds.std.did.DidInteropStandardApi;
import com.ctds.std.did.PlaceholderDidInteropStandardApi;
import com.ctds.std.evidence.EvidenceStandardApi;
import com.ctds.std.evidence.PlaceholderEvidenceStandardApi;
import com.ctds.std.interconnect.InterconnectStandardApi;
import com.ctds.std.interconnect.PlaceholderInterconnectStandardApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 标准适配层占位接线（WBS 2.4.8 H6）：注册三域占位实现供探活端点消费；
 * 后续工作包（3.1.x / 4.x / M2）交付真实规范实现时在此替换 Bean（占位替换规则见 ADR-008）。
 */
@Configuration
public class StdAdapterPlaceholderConfig {

    @Bean
    public InterconnectStandardApi interconnectStandardApi() {
        return new PlaceholderInterconnectStandardApi();
    }

    @Bean
    public DidInteropStandardApi didInteropStandardApi() {
        return new PlaceholderDidInteropStandardApi();
    }

    @Bean
    public EvidenceStandardApi evidenceStandardApi() {
        return new PlaceholderEvidenceStandardApi();
    }
}
