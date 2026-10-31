package com.ctds.delivery.application;

import com.ctds.delivery.domain.DeliveryType;

/** 发起交付命令（requesterNo 取 AuthContext，不收请求体传入）。 */
public record DeliveryCommand(String requesterNo, String contractNo, String deliveryType, String fileId,
        String purpose, String territory, String idempotencyKey, String connectorNo, String sourceNo) {

    public DeliveryType parsedType() {
        return DeliveryType.valueOf(deliveryType.trim());
    }
}
