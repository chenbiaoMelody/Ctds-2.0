package com.ctds.contract.interfaces.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 合约写面请求体（WBS-3.4.3 hifi §2.1；记录类只读绑定）。requestFingerprint 由控制器按
 * 条款值稳定哈希预计算（服务端派生幂等键成分——客户端不传）。
 */
public final class DealRequests {

    /** W5 发起合约（需求方；clauseValues = {slots, strategy} 原始节点，domain 严格解析）。 */
    public record InitiateContract(long productId, String templateNo, Integer templateVersionNo,
            JsonNode clauseValues) {
    }

    /** W6 提案/反提案（新条款值全文）。 */
    public record ProposeClause(JsonNode clauseValues) {
    }

    /** W9 电子签署（签署方主体 DID）。 */
    public record Sign(String did) {
    }

    /** W12 强制终止（理由必填 1~512）。 */
    public record ForceTermination(String reason) {
    }

    private DealRequests() {
    }
}
