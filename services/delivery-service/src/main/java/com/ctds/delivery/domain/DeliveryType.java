package com.ctds.delivery.domain;

/** 交付形态（规格行为 1 规则 3：API 合约只走 API 交付；数据集/报告/模型合约走文件交付）。 */
public enum DeliveryType {
    API,
    FILE
}
