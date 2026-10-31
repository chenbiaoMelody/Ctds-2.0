package com.ctds.contract.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单聚合（对应 product_order 表，WBS-3.7.3 hifi §2.1）：订单 = 合约业务外壳（规格 §2/Q3-A）。
 * 产品/定价/模板版本快照与合约落库同源（B3-3）；contract_no 关联合约草案（一笔订单对应一份
 * 合约）；active_order_key 为生成列（仅 IN_PROGRESS 行生成 需方#产品 键），不在聚合内承载。
 */
public record Order(Long id, String orderNo, long productId, String productName,
        String providerSubjectNo, String requesterSubjectNo, String pricingModel,
        BigDecimal priceAmount, String templateNo, int templateVersionNo, String contractNo,
        OrderStatus status, String closedReason, LocalDateTime closedAt, String createdBy,
        LocalDateTime createdAt, LocalDateTime updatedAt) {

    /** 参与方角色判定（读面归属校验单点；null = 非本方——沿 Contract.roleOf 先例）。 */
    public PartyRole roleOf(final String subjectNo) {
        if (subjectNo != null && subjectNo.equals(requesterSubjectNo)) {
            return PartyRole.REQUESTER;
        }
        if (subjectNo != null && subjectNo.equals(providerSubjectNo)) {
            return PartyRole.PROVIDER;
        }
        return null;
    }
}
