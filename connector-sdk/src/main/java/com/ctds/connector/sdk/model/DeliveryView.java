package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 交付视图（对齐平台交付域 DeliveryView 核心字段：deliveryNo / contractNo / productId /
 * productName / deliveryType / result）。交付记录 = 计量与存证统一事件源口径沿 C-5.1。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryView(String deliveryNo, String contractNo, Long productId, String productName,
        String deliveryType, String result) {
}
