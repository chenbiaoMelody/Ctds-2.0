package com.ctds.std;

/**
 * 标准能力域探活基础契约（WBS 2.4.8）：所有标准域接口的共同入口，永不抛错。
 * 骨架期各域 implemented=false；后续工作包按信通院规范文本在本模块内冻结具体协议方法并替换占位实现
 * （替换规则与收口纪律见 ADR-008）。
 */
public interface StdDomainApi {

    /** 本接口所属能力域。 */
    StdDomain domain();

    /** 域当前状态（是否开放 + 业务可读说明）。 */
    StdDomainStatus status();
}
