package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 交付视图（交付记录 = 计量与存证统一事件源口径沿 C-5.1）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryView(String deliveryNo, String contractNo, String deliveryType,
        String status, String result) {
}
