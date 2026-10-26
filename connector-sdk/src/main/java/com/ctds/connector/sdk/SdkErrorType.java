package com.ctds.connector.sdk;

/**
 * SDK 自身错误三分类（hifi §2 / lofi Q6-A）。
 *
 * <p>不占平台错误码 1010 段——码值归 3.6.3 落定（规格 C-6.1~6.3 V1.0 §7 Q9-A）。</p>
 */
public enum SdkErrorType {

    /** 平台不可达（连接失败 / 响应不完整）。 */
    UNREACHABLE,

    /** 平台调用超时（连接或读超时）。 */
    TIMEOUT,

    /** SDK 配置缺失或非法（fail-fast，不静默降级）。 */
    CONFIG
}
