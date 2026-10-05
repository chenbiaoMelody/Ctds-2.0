package com.ctds.contract.application;

/**
 * 发起合约结果（W5 成功响应，hifi §2.1）：发起即协商中（Q2-A），首版条款 V1 随发起落库。
 */
public record InitiateResult(String contractNo, String status, int clauseVersionNo,
        String templateNo, int templateVersionNo, long productId) {
}
