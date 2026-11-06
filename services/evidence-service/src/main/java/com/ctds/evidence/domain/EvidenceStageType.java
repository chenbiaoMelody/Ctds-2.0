package com.ctds.evidence.domain;

import com.ctds.common.errorcode.BizException;

/**
 * 存证环节类型（规格 C-8.1 B1-2：值域封闭 = 授权 / 合约 / 交付 / 使用四类，不设第五类）。
 */
public enum EvidenceStageType {

    /** 授权：空间成员准入 / 空间策略继承 / 公共数据授权类合约生效。 */
    AUTHORIZATION,
    /** 合约：合约签署生效（上游 contract_attestation 事件源，3.8.3 接入）。 */
    CONTRACT,
    /** 交付：交付记录（上游 delivery 事件源，3.8.3 接入）。 */
    DELIVERY,
    /** 使用：使用控制放行留痕与计量流水（拒绝调用不产生存证，规格 B1-3 / Q10）。 */
    USAGE;

    /** 解析封闭枚举；未知值 → 1012C0002（参数不合法）。 */
    public static EvidenceStageType parse(final String raw) {
        if (raw != null) {
            for (final EvidenceStageType value : values()) {
                if (value.name().equals(raw)) {
                    return value;
                }
            }
        }
        throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "环节类型不合法");
    }
}
