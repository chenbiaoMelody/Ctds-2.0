package com.ctds.contract.domain;

/**
 * 合约统一留痕动作码（hifi §4 contract_action_log.action，值域封闭 12 值；只插不改）。
 * 拒绝与异常同表承载（DENIED_ACCESS / VERIFY_SIGNATURE_FAILED），reason_code = 1008 码位尾号。
 */
public enum ContractAction {

    /** 发起合约（W5；to = 版本号 1）。 */
    CREATE,
    /** 提案/反提案（W6；from = 旧版本号，to = 新版本号）。 */
    PROPOSE,
    /** 确认当前条款版本（W7；to = 确认版本号；重复确认拒绝同码 C0013 留痕）。 */
    CONFIRM,
    /** 协商终止（W8；from = 旧状态，to = TERMINATED）。 */
    TERMINATE_NEGOTIATION,
    /** 电子签署（W9；to = 新状态 PARTIALLY_SIGNED/EFFECTIVE）。 */
    SIGN,
    /** 拒签终止（W10；from = 旧状态，to = TERMINATED）。 */
    REFUSE_SIGN,
    /** 生效存证登记（W9 后签事务内；随存证事件同批落）。 */
    ATTEST,
    /** 合意解除确认（W11；每方一条，第二方 to = COMPLETED）。 */
    RELEASE_CONSENT,
    /** 运营方强制终止（W12；from = EFFECTIVE，to = TERMINATED；理由落 contract 列）。 */
    FORCE_TERMINATE,
    /** 治理查看（R10 列表/R11 详情；每次调用一条，实际登录主体为操作者）。 */
    GOVERNANCE_VIEW,
    /** 验签异常（R9 逐方 FAIL/UNAVAILABLE 处置留痕；reason = C0018/S0002 尾号）。 */
    VERIFY_SIGNATURE_FAILED,
    /** 越权拒绝（非参与方读写/治理越权——404 同形防枚举与 C0011 的拒绝留痕）。 */
    DENIED_ACCESS
}
