package com.ctds.evidence.support;

import com.ctds.evidence.domain.ChainEntry;
import com.ctds.evidence.domain.ChainPayload;
import com.ctds.evidence.domain.ChainReceipt;
import com.ctds.evidence.domain.EvidenceChainUnavailableException;
import com.ctds.evidence.domain.EvidenceGateway;
import com.ctds.evidence.infrastructure.LocalLedgerEvidenceGateway;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 可控失败网关（T6 / T5 双留痕用例的唯一故障构造手段）：默认委托本地账本；
 * 置 {@link #FAIL_NEXT} 后下一次 submit 抛"链不可达"，消费一次即复位。
 * 生产代码无运行期故障开关（Q7-A 无后门）。
 */
@TestConfiguration
public class FlakyChainGatewayConfiguration {

    public static final AtomicBoolean FAIL_NEXT = new AtomicBoolean(false);

    @Bean
    @Primary
    public EvidenceGateway flakyEvidenceGateway(final LocalLedgerEvidenceGateway delegate) {
        return new EvidenceGateway() {
            @Override
            public ChainReceipt submit(final ChainPayload payload) {
                if (FAIL_NEXT.compareAndSet(true, false)) {
                    throw new EvidenceChainUnavailableException("测试构造：存证链不可达");
                }
                return delegate.submit(payload);
            }

            @Override
            public Optional<ChainEntry> findByTxId(final String chainTxId) {
                return delegate.findByTxId(chainTxId);
            }
        };
    }
}
