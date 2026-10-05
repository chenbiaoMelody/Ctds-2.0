package com.ctds.contract.domain;

/**
 * 合约生命周期写动作（状态机全转移表的行键，hifi §5；与 {@link ContractAction} 分列——
 * 本枚举只服务状态门槛判定，留痕动作码独立封闭）。
 */
public enum LifecycleAction {

    /** 提案/反提案（W6）。 */
    PROPOSE,
    /** 确认当前条款版本（W7）。 */
    CONFIRM,
    /** 协商终止（W8）。 */
    NEGOTIATION_TERMINATE,
    /** 电子签署（W9，待签署/部分签署两态可入）。 */
    SIGN,
    /** 拒签终止（W10，未双签生效前两态可入——Q4-A）。 */
    REFUSE_SIGN,
    /** 合意解除确认（W11）。 */
    RELEASE_CONSENT,
    /** 运营方强制终止（W12）。 */
    FORCE_TERMINATE
}
