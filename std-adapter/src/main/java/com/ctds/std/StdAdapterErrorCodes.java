package com.ctds.std;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCode;

/**
 * 标准适配层码表（模块位 1003，顺延 ADR-006 1001 / ADR-007 1002 规划；契约见 ADR-008
 * 与 docs/designs/WBS-2.4.8-hifi.md）。
 * 对外文案为服务端常量，禁止拼接域名/用户输入/内部实现（红线：不暴露内部实现）。
 */
public final class StdAdapterErrorCodes {

    /** 能力域尚未交付即被调用。→ 400 */
    public static final ErrorCode NOT_IMPLEMENTED = ErrorCode.of("1003C0001");

    /** 对外文案（C 码出站展示；三域统一，域标识仅留在调用方代码内部）。 */
    public static final String NOT_IMPLEMENTED_MESSAGE = "该标准互联功能尚未开放";

    /** 认证渠道技术异常（不可用/超时等基础设施故障，WBS-3.1.3 增设、ADR-008 补记）。S 型语义，出站文案由调用方转译。 */
    public static final ErrorCode CHANNEL_UNAVAILABLE = ErrorCode.of("1003S0001");

    /** 渠道技术异常内部文案（不出站：S 型码对外统一脱敏，调用方转译为自身错误码与文案）。 */
    public static final String CHANNEL_UNAVAILABLE_MESSAGE = "认证渠道调用失败";

    /**
     * 构造"能力未开放"业务异常：domain 为 null → 编程错误快速失败；
     * 出站文案为码表常量，不携带域信息（内部实现不外露）。
     */
    public static BizException notImplemented(final StdDomain domain) {
        if (domain == null) {
            throw new IllegalArgumentException("domain must not be null");
        }
        return new BizException(NOT_IMPLEMENTED, NOT_IMPLEMENTED_MESSAGE);
    }

    /** 构造渠道技术异常（规格 C-1.1 行为 7 第 4 条：渠道异常与业务不通过严格区分，调用方 fail-fast 且不计业务失败次数）。 */
    public static BizException channelUnavailable() {
        return new BizException(CHANNEL_UNAVAILABLE, CHANNEL_UNAVAILABLE_MESSAGE);
    }

    private StdAdapterErrorCodes() {
    }
}
