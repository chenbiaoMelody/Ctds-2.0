package com.ctds.connector.sdk.adapter.gov;

import com.ctds.connector.sdk.spi.SourceAdapter;
import com.ctds.connector.sdk.spi.SourceCheckFailureType;
import com.ctds.connector.sdk.spi.SourceCheckResult;
import com.ctds.connector.sdk.spi.SourceReadRequest;
import com.ctds.connector.sdk.spi.SourceReadResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import java.time.Duration;
import org.springframework.web.client.ResourceAccessException;

/**
 * 政务共享交换平台适配器（规格行为 11 规则 1：适配器随连接器分发；本卡唯一适配器实现）。
 *
 * <p>承载：{@link SourceType#GOV_EXCHANGE}。自检与读取均按标准报文交互——{@code mock://} 走
 * 进程内模拟源（V1.0 正式承载，界面如实标注"模拟"），{@code http(s)://} 走真实 HTTP（未与真实平台
 * 联调，协议为本地约定）。</p>
 *
 * <p>只读语义：{@code check} 与 {@code read} 均不产生源端数据变更；自检通过不构成业务授权
 * （判定与取数次序归交付链 3.6.8）。凭据经 {@link CredentialResolver} 于调用期解析，
 * 明文不出本方法，不进日志 / 异常 / 摘要。</p>
 */
public final class GovExchangeSourceAdapter implements SourceAdapter {

    private static final String READ_FAILURE_PREFIX = "读取失败：";

    private final CredentialResolver credentialResolver;
    private final GovExchangeClient client;
    private final MockGovExchangeSource mockSource = new MockGovExchangeSource();

    public GovExchangeSourceAdapter(final CredentialResolver credentialResolver, final Duration timeout) {
        this.credentialResolver = credentialResolver;
        this.client = new GovExchangeClient(timeout);
    }

    @Override
    public SourceType type() {
        return SourceType.GOV_EXCHANGE;
    }

    @Override
    public SourceCheckResult check(final SourceRegistration registration) {
        try {
            final GovExchangeMessage response = exchange(registration,
                    GovExchangeMessage.request(registration, null, null));
            if (GovExchangeMessage.OK.equals(response.code())) {
                return SourceCheckResult.ok();
            }
            return SourceCheckResult.fail(failureTypeOf(response.code()), response.message());
        } catch (final ResourceAccessException e) {
            return SourceCheckResult.fail(SourceCheckFailureType.NETWORK_UNREACHABLE, "网络不可达");
        }
    }

    @Override
    public SourceReadResult read(final SourceRegistration registration, final SourceReadRequest request) {
        try {
            final GovExchangeMessage response = exchange(registration,
                    GovExchangeMessage.request(registration, request.contractNo(), request.purpose()));
            if (GovExchangeMessage.OK.equals(response.code())) {
                return new SourceReadResult(true, response.message());
            }
            return new SourceReadResult(false, READ_FAILURE_PREFIX + response.message());
        } catch (final ResourceAccessException e) {
            return new SourceReadResult(false, READ_FAILURE_PREFIX + "网络不可达");
        }
    }

    /** 模拟源触达计数（可证伪断言：前置拒绝时适配器零调用）。 */
    public int mockExchangeCount() {
        return mockSource.exchangeCount();
    }

    /** 模拟源端数据版本（可证伪断言：自检 / 读取零源端变更）。 */
    public int mockDataVersion() {
        return mockSource.dataVersion();
    }

    /** 重置模拟源观测计数（演示与用例隔离）。 */
    public void mockResetCounters() {
        mockSource.reset();
    }

    private GovExchangeMessage exchange(final SourceRegistration registration,
            final GovExchangeMessage request) {
        final String credential = credentialResolver.resolve(registration.credentialRef());
        if (registration.endpoint() != null && registration.endpoint().startsWith(MockGovExchangeSource.MOCK_PREFIX)) {
            return mockSource.exchange(request);
        }
        return client.exchange(request, credential);
    }

    private static SourceCheckFailureType failureTypeOf(final String code) {
        return switch (code) {
            case GovExchangeMessage.INVALID_CREDENTIAL -> SourceCheckFailureType.INVALID_CREDENTIAL;
            case GovExchangeMessage.INSUFFICIENT_PERMISSION -> SourceCheckFailureType.INSUFFICIENT_PERMISSION;
            default -> SourceCheckFailureType.NETWORK_UNREACHABLE;
        };
    }
}
