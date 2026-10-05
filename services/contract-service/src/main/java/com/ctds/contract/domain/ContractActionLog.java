package com.ctds.contract.domain;

import java.time.LocalDateTime;

/**
 * 合约统一留痕行（对应 contract_action_log 表，hifi §4 表 5）：四要素（谁/何时/哪个合约+版本/
 * 动作）+ 拒绝理由码（1008 码位尾号，DB-31 口径）+ from→to（版本号或状态）。
 * 只插不改；不含敏感原文（值级明细在加密版本行可查——行为 7 规则 5）。
 */
public record ContractActionLog(Long id, String contractNo, Integer versionNo, ContractAction action,
        String actorSubjectNo, String reasonCode, String fromValue, String toValue,
        LocalDateTime createdAt) {
}
