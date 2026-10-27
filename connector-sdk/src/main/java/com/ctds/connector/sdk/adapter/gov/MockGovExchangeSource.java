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
 * <p>观测面（可证伪断言用）：触达计数证明适配器确实与源交互；源端数据版本仅在实际写入时递增——
 * V1.0 SPI 只读（check / read 无写方法），自检前后版本不变即证明"自检零源端变更"。</p>
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
