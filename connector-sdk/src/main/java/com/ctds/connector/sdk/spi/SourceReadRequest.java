package com.ctds.connector.sdk.spi;

/**
 * 源数据读取请求（规格行为 14）。
 *
 * @param contractNo 合约编号（读取服务于平台交付链）
 * @param purpose    用途
 */
public record SourceReadRequest(String contractNo, String purpose) {
}
