package com.ctds.connector.sdk.model;

import java.util.Map;

/**
 * 合约磋商创建命令（对齐平台 DealRequests.InitiateContract：productId / templateNo /
 * templateVersionNo（≥1）/ clauseValues 条款值全文）。
 */
public record CreateContractCommand(Long productId, String templateNo, Integer templateVersionNo,
        Map<String, Object> clauseValues) {
}
