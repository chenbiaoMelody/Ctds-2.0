package com.ctds.connector.sdk.capability;

import com.ctds.connector.sdk.model.DeliveryRequest;
import com.ctds.connector.sdk.model.DeliveryView;

/**
 * 交付门面（规格行为 4）：SDK 为<b>需求方侧发起端</b>——凭据 = 已生效合约
 * （未生效 / 已终止 / 已完结合约由平台按合约域状态门槛拒绝，SDK 原样接收拒绝语义）；
 * V1.0 数据交付唯一合规通道 = 平台网关交付链（点对点直传不在范围）。
 */
public interface DeliveryCapability {

    /** 发起交付（幂等键自动携带；凭据三件随请求声明）。 */
    DeliveryView requestDelivery(DeliveryRequest request);

    /** 查交付详情。 */
    DeliveryView getDelivery(String deliveryNo);
}
