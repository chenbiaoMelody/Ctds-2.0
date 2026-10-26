package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 交付发起请求（行为 4）：凭据 = 已生效合约；deliveryType = API / FILE
 * （平台交付域口径）；幂等键缺省时 SDK 自动生成并同请求复用。
 */
public record DeliveryRequest(String contractNo, String deliveryType, String fileId,
        String purpose, String territory, @JsonIgnore String idempotencyKey) {

    /** API 型交付（使用腿同构口径）。 */
    public static DeliveryRequest api(String contractNo, String purpose, String territory) {
        return new DeliveryRequest(contractNo, "API", null, purpose, territory, null);
    }
}
