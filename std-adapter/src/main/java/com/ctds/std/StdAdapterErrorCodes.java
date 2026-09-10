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

    private StdAdapterErrorCodes() {
    }
}
