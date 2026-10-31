package com.ctds.delivery.interfaces.dto;

/**
 * 发起交付请求体（POST /api/v1/deliveries）；幂等键走 X-Idempotency-Key 头。
 * WBS-3.6.8：CONNECTOR 形态必填 {@code connectorNo}/{@code sourceNo}，其余形态禁带（互斥校验在服务层）。
 */
public record DeliveryRequest(String contractNo, String deliveryType, String fileId, String purpose,
        String territory, String connectorNo, String sourceNo) {
}
