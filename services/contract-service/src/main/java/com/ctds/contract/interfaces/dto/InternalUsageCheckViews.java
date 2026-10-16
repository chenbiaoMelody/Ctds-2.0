package com.ctds.contract.interfaces.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内部判定面出站视图（WBS-3.5.2 hifi §4.1，ADR-016 §6 第 6 消费方 = 网关交付链）：
 * 判定摘要 + 参与方摘要，<b>不含合约条款原文与数据本体</b>。
 */
public final class InternalUsageCheckViews {

    private InternalUsageCheckViews() {
    }

    /**
     * 判定请求体：{@code requesterNo} = 判定主体（网关侧取自 JWT {@code sub}，不依赖身份头）；
     * {@code actionType} 交付调用面固定 {@code USE}；{@code purpose}/{@code territory} 为策略要素（可空）。
     */
    public record Request(String contractNo, String requesterNo, String actionType, String purpose,
            String territory) {
    }

    /** 判定结果 + 合约参与方摘要（{@code applicantNo} = 合约需求方，供交付域校验"发起人 = 需求方"）。 */
    public record Result(Verdict verdict, ContractSummary contract) {
    }

    /** 判定摘要（allowed / usedCount / violations / occurredAt；与 {@code UsageVerdict} 同源同值）。 */
    public record Verdict(boolean allowed, int usedCount, List<String> violations,
            LocalDateTime occurredAt) {
    }

    /** 参与方摘要（不含条款原文与数据本体）。 */
    public record ContractSummary(String applicantNo, String providerNo, String status,
            LocalDateTime effectiveAt) {
    }
}
