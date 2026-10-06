package com.ctds.contract.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 合约状态机全转移表（行为 6 规则 1，单点承载——应用服务与仓储的条件更新一律经此判定口径，
 * Guard 单测遍历全表防漂移，hifi §7 U 锚）。终态（COMPLETED/TERMINATED）零出边（规则 2）；
 * 本表只回答"该状态是否允许该类写动作"，参与方/资格/交替等业务门槛在应用服务单点。
 */
public final class ContractTransitions {

    private static final Map<LifecycleAction, Set<ContractStatus>> ALLOWED =
            new EnumMap<>(LifecycleAction.class);

    static {
        ALLOWED.put(LifecycleAction.PROPOSE, EnumSet.of(ContractStatus.NEGOTIATING));
        ALLOWED.put(LifecycleAction.CONFIRM, EnumSet.of(ContractStatus.NEGOTIATING));
        ALLOWED.put(LifecycleAction.NEGOTIATION_TERMINATE, EnumSet.of(ContractStatus.NEGOTIATING));
        ALLOWED.put(LifecycleAction.SIGN,
                EnumSet.of(ContractStatus.PENDING_SIGNATURE, ContractStatus.PARTIALLY_SIGNED));
        ALLOWED.put(LifecycleAction.REFUSE_SIGN,
                EnumSet.of(ContractStatus.PENDING_SIGNATURE, ContractStatus.PARTIALLY_SIGNED));
        ALLOWED.put(LifecycleAction.RELEASE_CONSENT, EnumSet.of(ContractStatus.EFFECTIVE));
        ALLOWED.put(LifecycleAction.FORCE_TERMINATE, EnumSet.of(ContractStatus.EFFECTIVE));
    }

    /** 该状态下是否允许该类写动作（终态恒 false——零出边）。 */
    public static boolean allowed(final LifecycleAction action, final ContractStatus status) {
        return ALLOWED.get(action).contains(status);
    }

    private ContractTransitions() {
    }
}
