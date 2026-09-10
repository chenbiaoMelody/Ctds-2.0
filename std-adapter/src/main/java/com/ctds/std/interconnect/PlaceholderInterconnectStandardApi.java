package com.ctds.std.interconnect;

import com.ctds.std.StdDomain;
import com.ctds.std.StdDomainStatus;

/**
 * 互联互通域占位实现（WBS 2.4.8）：状态 = 未开放，纯常量返回、无状态无副作用。
 * WBS 4.x 交付真实规范实现时替换本类的注册（占位替换规则见 ADR-008），届时本类删除。
 */
public class PlaceholderInterconnectStandardApi implements InterconnectStandardApi {

    @Override
    public StdDomain domain() {
        return StdDomain.INTERCONNECT;
    }

    @Override
    public StdDomainStatus status() {
        return new StdDomainStatus(StdDomain.INTERCONNECT, false,
                "该能力域尚未开放：区域枢纽对接与产品互挂接口将按信通院互联互通规范由后续工作包实现（WBS 4.x）");
    }
}
