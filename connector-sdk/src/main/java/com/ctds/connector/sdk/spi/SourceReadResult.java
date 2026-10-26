package com.ctds.connector.sdk.spi;

/**
 * 源数据读取结果（摘要口径——<b>不含数据本体与原文</b>，留痕沿平台统一事件源）。
 *
 * @param success 是否成功
 * @param summary 摘要说明（谁 / 哪个源 / 哪份合约 / 结果）
 */
public record SourceReadResult(boolean success, String summary) {
}
