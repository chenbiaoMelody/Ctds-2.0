package com.ctds.delivery.interfaces.dto;

/** 发起交付请求体（POST /api/v1/deliveries）；幂等键走 X-Idempotency-Key 头。 */
public record DeliveryRequest(String contractNo, String deliveryType, String fileId, String purpose,
        String territory) {
}
