package com.ctds.connector.sdk.adapter.apigateway;

/**
 * API 网关只读探测结果（hifi §3，包私有）：状态码 / 读取字节数 / 内容类型。
 *
 * <p>不含任何响应内容——摘要口径只允许"状态码 + 字节数 + 内容类型"（行为 14；hifi §7.5）。</p>
 *
 * @param status      应答状态码
 * @param bytes       读取的响应体字节数（自检固定 0；上限 1MB）
 * @param contentType 应答内容类型（可空串）
 */
record HttpProbeResult(int status, long bytes, String contentType) {
}
