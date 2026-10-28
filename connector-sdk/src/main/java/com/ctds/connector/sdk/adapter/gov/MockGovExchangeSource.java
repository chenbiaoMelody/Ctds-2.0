package com.ctds.connector.sdk.adapter.gov;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 政务共享交换平台<b>标准报文模拟源</b>（V1.0 正式承载形态，规格 §7 Q7-A / 行为 11 规则 3）。
 *
 * <p>仅 {@code mock://gov-exchange/**} 生效，进程内按标准报文应答；<b>界面与文档一律标注"模拟"</b>，
 * 不虚称已对接真实政务平台（生产对接属部署实施期）。</p>
 *
 * <p>演示 / 测试共用五类路径（hifi §7-⑤）：{@code /ok} 通过、{@code /bad-credential} 凭据无效、
 * {@code /unreachable} 网络不可达、{@code /forbidden} 权限不足、其余路径视为正常源。</p>
 *
 * <p>观测面（可证伪断言用）：触达计数证明适配器确实与源交互；源端数据版本仅在
 * {@link #applyWrite} 被调用时递增（V1.0 SPI 只读，check / read 路径永不调用写方法）。
 * 该写入入口只供用例证明"版本计数是活的"——否则"自检后版本仍为 0"将是无失败可能的恒真断言。</p>
 */
final class MockGovExchangeSource {

    static final String MOCK_PREFIX = "mock://gov-exchange/";
    private static final String OK_MESSAGE = "模拟交换报文 1 件（摘要，不含数据本体）";

    private final AtomicInteger exchangeCount = new AtomicInteger();
    private final AtomicInteger dataVersion = new AtomicInteger();

    /** 按路径应答（只读；不修改源端数据版本）。 */
    GovExchangeMessage exchange(final GovExchangeMessage request) {
        exchangeCount.incrementAndGet();
        return switch (pathOf(request.endpoint())) {
            case "bad-credential" -> GovExchangeMessage.failure(GovExchangeMessage.INVALID_CREDENTIAL, "凭据无效");
            case "unreachable" -> GovExchangeMessage.failure(GovExchangeMessage.NETWORK_UNREACHABLE, "网络不可达");
            case "forbidden" -> GovExchangeMessage.failure(
                    GovExchangeMessage.INSUFFICIENT_PERMISSION, "权限不足");
            default -> new GovExchangeMessage(request.sourceNo(), request.endpoint(),
                    request.contractNo(), request.purpose(), 1, GovExchangeMessage.OK, OK_MESSAGE);
        };
    }

    /** 模拟源写入（只由用例调用，用于证明 {@link #dataVersion()} 可观测到变更；适配器生产路径不调用）。 */
    GovExchangeMessage applyWrite(final GovExchangeMessage request) {
        dataVersion.incrementAndGet();
        return new GovExchangeMessage(request.sourceNo(), request.endpoint(),
                request.contractNo(), request.purpose(), 1, GovExchangeMessage.OK, OK_MESSAGE);
    }

    int exchangeCount() {
        return exchangeCount.get();
    }

    int dataVersion() {
        return dataVersion.get();
    }

    void reset() {
        exchangeCount.set(0);
        dataVersion.set(0);
    }

    private static String pathOf(final String endpoint) {
        if (endpoint == null || !endpoint.startsWith(MOCK_PREFIX)) {
            return "";
        }
        return endpoint.substring(MOCK_PREFIX.length());
    }
}
