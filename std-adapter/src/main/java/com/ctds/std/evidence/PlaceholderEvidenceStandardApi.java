package com.ctds.std.evidence;

import com.ctds.std.StdDomain;
import com.ctds.std.StdDomainStatus;

/**
 * 测评证据域占位实现（WBS 2.4.8）：状态 = 未开放，纯常量返回、无状态无副作用。
 * M2 测评演练工作包交付真实实现时替换本类的注册（占位替换规则见 ADR-008），届时本类删除。
 */
public class PlaceholderEvidenceStandardApi implements EvidenceStandardApi {

    @Override
    public StdDomain domain() {
        return StdDomain.EVIDENCE;
    }

    @Override
    public StdDomainStatus status() {
        return new StdDomainStatus(StdDomain.EVIDENCE, false,
                "该能力域尚未开放：测评证据采集与互联互通契约测试将由测评演练工作包实现（M2）");
    }
}
