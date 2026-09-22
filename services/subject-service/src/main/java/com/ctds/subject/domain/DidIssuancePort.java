package com.ctds.subject.domain;

/**
 * DID 签发触发端口（WBS-3.1.8，ADR-016 §6 衔接契约）：主体状态到达 ADMITTED 后触发签发。
 * 失败抛异常，由应用层触发器收敛为仅记 WARN（不影响审核结论，hifi §4.3）。
 */
public interface DidIssuancePort {

    void triggerIssuance(String subjectNo, String subjectName, String subjectType);
}
