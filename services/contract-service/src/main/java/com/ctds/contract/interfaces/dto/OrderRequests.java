package com.ctds.contract.interfaces.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 订单域请求体（WBS-3.7.3 hifi §1.1 W-OR1）。clauseValues 形态与 W5 发起逐字一致
 * （slots 槽位值 + strategy 五要素）——校验由合约发起链路单点承担，订单不复制。
 */
public final class OrderRequests {

    /** W-OR1 创建订单请求（requestId 客户端弹窗生成一次；幂等键 = 需方 + requestId）。 */
    public record Create(Long productId, String templateNo, Integer templateVersionNo,
            JsonNode clauseValues, String requestId) {
    }

    private OrderRequests() {
    }
}
