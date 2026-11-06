package com.ctds.evidence.domain;

import com.ctds.common.errorcode.BizException;

/**
 * 存证状态机（规格 B2-3）：PENDING → ON_CHAIN / FAILED；FAILED 可重试回 ON_CHAIN；
 * ON_CHAIN 为终态（再重试 → 409 1012C0003）。
 */
public enum EvidenceStatus {

    /** 待上链（正常写入窗口极短；崩溃窗口态）。 */
    PENDING,
    /** 已上链（终态）。 */
    ON_CHAIN,
    /** 上链失败（fail-visible，可重试）。 */
    FAILED;

    /** 解析状态过滤参数；未知值 → 1012C0002。 */
    public static EvidenceStatus parse(final String raw) {
        if (raw != null) {
            for (final EvidenceStatus value : values()) {
                if (value.name().equals(raw)) {
                    return value;
                }
            }
        }
        throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "状态不合法");
    }
}
