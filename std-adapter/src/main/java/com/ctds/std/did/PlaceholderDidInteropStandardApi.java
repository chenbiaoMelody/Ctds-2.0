package com.ctds.std.did;

import com.ctds.std.StdDomain;
import com.ctds.std.StdDomainStatus;

/**
 * DID 互认域占位实现（WBS 2.4.8）：状态 = 未开放，纯常量返回、无状态无副作用。
 * WBS 3.1.x 交付真实实现时替换本类的注册（占位替换规则见 ADR-008），届时本类删除。
 */
public class PlaceholderDidInteropStandardApi implements DidInteropStandardApi {

    @Override
    public StdDomain domain() {
        return StdDomain.DID_INTEROP;
    }

    @Override
    public StdDomainStatus status() {
        return new StdDomainStatus(StdDomain.DID_INTEROP, false,
                "该能力域尚未开放：政务 CA 接入与跨空间身份互认接口将由后续工作包实现（WBS 3.1.4 / 3.1.10）");
    }
}
