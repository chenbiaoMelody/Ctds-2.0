package com.ctds.evidence.domain;

import com.ctds.common.errorcode.BizException;

/**
 * 存证操作类型（规格 B1-2：六类封闭枚举，且必须与环节匹配——授权三类 / 合约一类 / 交付一类 / 使用一类）。
 */
public enum EvidenceOperationType {

    SPACE_MEMBER_ADMITTED(EvidenceStageType.AUTHORIZATION),
    SPACE_POLICY_INHERITED(EvidenceStageType.AUTHORIZATION),
    DATA_AUTH_CONTRACT_EFFECTIVE(EvidenceStageType.AUTHORIZATION),
    CONTRACT_EFFECTIVE(EvidenceStageType.CONTRACT),
    DELIVERY_CREATED(EvidenceStageType.DELIVERY),
    USAGE_ALLOWED(EvidenceStageType.USAGE);

    private final EvidenceStageType stageType;

    EvidenceOperationType(final EvidenceStageType stageType) {
        this.stageType = stageType;
    }

    public EvidenceStageType stageType() {
        return stageType;
    }

    /** 解析封闭枚举并与环节匹配；未知值 / 错配 → 1012C0002。 */
    public static EvidenceOperationType parse(final String raw, final EvidenceStageType stageType) {
        if (raw != null) {
            for (final EvidenceOperationType value : values()) {
                if (value.name().equals(raw)) {
                    if (value.stageType != stageType) {
                        throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "操作类型与环节不匹配");
                    }
                    return value;
                }
            }
        }
        throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "操作类型不合法");
    }
}
