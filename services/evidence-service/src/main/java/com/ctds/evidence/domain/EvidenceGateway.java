package com.ctds.evidence.domain;

import java.util.Optional;

/**
 * 存证网关抽象（ADR-004 §3-2：业务代码只依赖本接口，换链只改适配器）。
 * 本卡默认实现 = 本地不可变哈希链账本；真实 FISCO BCOS 适配器为条件项（Q1-A）。
 */
public interface EvidenceGateway {

    /** 写入存证载荷；链不可达抛 {@link EvidenceChainUnavailableException}（禁止静默吞）。 */
    ChainReceipt submit(ChainPayload payload);

    /**
     * 按交易标识取账本条目；**账本不可达抛 {@link EvidenceChainUnavailableException}**
     * （映射 503 1012S0001，不得伪报为空）；账本可达但查无此块 = 空。
     */
    Optional<ChainEntry> findByTxId(String chainTxId);
}
