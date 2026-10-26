package com.ctds.connector.sdk.capability;

import com.ctds.connector.sdk.model.DeliveryView;
import com.ctds.connector.sdk.model.FileDeliveryRequest;
import com.ctds.connector.sdk.model.ProductAccessRequest;
import com.ctds.connector.sdk.model.ProductView;

/**
 * 交付门面（规格行为 4）：SDK 为<b>需求方侧发起端</b>——凭据 = 已生效合约（未生效 / 已终止 /
 * 已完结合约由平台按合约域状态门槛拒绝，SDK 原样接收拒绝语义）。
 *
 * <p>API 型交付 = 经<b>平台网关交付面</b>请求被交付资源（C-5.1 行为 2 / 3.5.2 Q4-A 演示探针口径：
 * 判定在网关转发前由平台完成）；文件型交付出站交付域记录端点（V1.0 经网关不可达，登记 T1，
 * 端到端受理验证归 3.6.8）。</p>
 */
public interface DeliveryCapability {

    /** API 型交付：经网关交付面请求被交付资源；放行返回资源视图，拒绝原码透传。 */
    ProductView requestApiDelivery(ProductAccessRequest request);

    /** 文件型交付（ADR-023）：幂等键必填（重试复用同一键）；V1.0 经网关不可达（T1）。 */
    DeliveryView requestFileDelivery(FileDeliveryRequest request);

    /** 查交付详情（V1.0 经网关不可达，登记 T1）。 */
    DeliveryView getDelivery(String deliveryNo);
}
