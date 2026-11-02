package com.ctds.metering.domain.billing;

/**
 * 账单读面过滤条件（hifi §1 B-R1/B-R3）：全部可选；本方读面额外由服务端注入当前主体做归属硬校验，
 * 治理读面可按双方主体过滤（B12-1/B12-2）。
 */
public record BillSearchCriteria(
        String contractNo,
        String period,
        Long productId,
        String requesterNo,
        String providerNo,
        BillStatus status,
        BillType billType) {
}
