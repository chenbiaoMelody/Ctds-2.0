package com.ctds.contract.application;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 发起合约命令（W5，hifi §2.1）：幂等键 = requesterNo + productId + templateNo +
 * templateVersionNo + requestFingerprint（服务端派生——requestFingerprint 由控制器按条款值
 * 稳定哈希预计算，幂等切面 SpEL 只读绑定，沿 3.4.2 V1.1 §2.1 口径）；clauseValues 为原始
 * 请求节点（domain 严格解析）。
 */
public record InitiateCommand(String requesterNo, long productId, String templateNo,
        int templateVersionNo, JsonNode clauseValues, String requestFingerprint) {
}
