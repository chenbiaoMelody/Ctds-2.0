package com.ctds.connector.sdk.spi;

/**
 * 适配器承载的源类型（规格行为 11 规则 1 / §7 Q7-A）：V1.0 多源适配四类源全量。
 *
 * <p>政务共享交换平台 V1.0 以标准报文模拟源承载（诚实声明——真实政务平台生产对接属部署实施期）。</p>
 */
public enum SourceType {

    /** 政务共享交换平台（适配器实现归 3.6.5）。 */
    GOV_EXCHANGE,

    /** 数据库（归 3.6.6）。 */
    DATABASE,

    /** 对象存储（归 3.6.6）。 */
    OBJECT_STORAGE,

    /** API 网关（归 3.6.6）。 */
    API_GATEWAY
}
