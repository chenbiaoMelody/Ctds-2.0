package com.ctds.contract.interfaces.dto;

import com.ctds.contract.domain.ContractActionLog;
import java.time.LocalDateTime;

/**
 * 合约统一留痕视图（R13；WBS-3.4.7 hifi §1.2：四要素 + 版本号 + from→to + 拒绝理由码——
 * 三剧本"留痕四要素齐备"类判定面屏幕可核对；字段白名单封闭，不含敏感原文、签名值与条款明文
 * 〔表本身不含——值级明细在加密版本行〕）。
 */
public record ContractActionLogView(
        Long id,
        String contractNo,
        Integer versionNo,
        String action,
        String actorSubjectNo,
        String reasonCode,
        String fromValue,
        String toValue,
        LocalDateTime createdAt) {

    /** 领域 → 出站映射。 */
    public static ContractActionLogView from(final ContractActionLog log) {
        return new ContractActionLogView(log.id(), log.contractNo(), log.versionNo(),
                log.action().name(), log.actorSubjectNo(), log.reasonCode(), log.fromValue(),
                log.toValue(), log.createdAt());
    }
}
