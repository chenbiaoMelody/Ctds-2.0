package com.ctds.contract.domain;

import com.ctds.common.crypto.Sm3Service;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;

/**
 * 合约规范化与内容哈希（WBS-3.4.3 hifi §6.2）：固定字段序紧凑 JSON（UTF-8）→ SM3 摘要
 * （common-crypto 唯一入口）。规范化不依赖用户键序——槽位键排序 + 策略固定字段序；
 * 数值（次数）以 JSON number 承载、定价以 plain 字符串承载（免费 = 空串）。
 * 链上/库内一致性：锁定时规范化原文密文（canonical_cipher）与内容哈希（content_hash）同批
 * 固化，T5 断言"解密原文重算哈希 = 落库哈希"。
 */
public final class ContractCanonicalizer {

    /**
     * 规范化 JSON（签名哈希输入原件）：contractNo / productId / productName / providerSubjectNo /
     * requesterSubjectNo / templateNo / templateVersionNo / pricingModel / priceAmount（plain 字符串）
     * / slots（槽位键排序）/ strategy（固定字段序）。
     */
    public static String canonicalJson(final Contract contract, final ClauseValues values) {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("contractNo", contract.contractNo());
        root.put("productId", contract.productId());
        root.put("productName", contract.productName());
        root.put("providerSubjectNo", contract.providerSubjectNo());
        root.put("requesterSubjectNo", contract.requesterSubjectNo());
        root.put("templateNo", contract.templateNo());
        root.put("templateVersionNo", contract.templateVersionNo());
        root.put("pricingModel", contract.pricingModel());
        final BigDecimal priceAmount = contract.priceAmount();
        root.put("priceAmount", priceAmount == null ? "" : priceAmount.toPlainString());
        root.set("slots", values.toStorageJson().get("slots"));
        root.set("strategy", values.strategy().toJson());
        return root.toString();
    }

    /** 内容哈希（SM3 hex，64 位小写十六进制；不可逆摘要明文落库——登记口径）。 */
    public static String contentHash(final String canonicalJson, final Sm3Service sm3) {
        return sm3.digestHex(canonicalJson);
    }

    private ContractCanonicalizer() {
    }
}
