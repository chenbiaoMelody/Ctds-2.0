package com.ctds.delivery.domain;

/** 交付形态（规格行为 1 规则 3：API 合约只走 API 交付；数据集/报告/模型合约走文件交付）。 */
public enum DeliveryType {
    API,
    FILE,
    /** 沙箱结果物审批后导出（WBS-3.5.5；导出记录复用 deliveries 单一交付事件源）。 */
    SANDBOX_EXPORT,
    /** 连接器真实源交付（WBS-3.6.8 W3；规格行为 14：先判定后取数——经连接器适配器从真实源取数）。 */
    CONNECTOR
}
