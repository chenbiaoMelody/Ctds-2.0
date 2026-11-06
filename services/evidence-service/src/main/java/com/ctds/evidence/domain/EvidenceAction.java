package com.ctds.evidence.domain;

/**
 * 状态流转与核验留痕动作（hifi §2 `evidence_record_action_log.action` 六类封闭枚举）。
 */
public enum EvidenceAction {

    CREATED,
    CHAIN_SUCCEEDED,
    CHAIN_FAILED,
    RETRY_SUCCEEDED,
    RETRY_FAILED,
    VERIFIED
}
