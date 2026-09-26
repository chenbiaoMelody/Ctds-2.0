package com.ctds.space.domain;

/**
 * 主体入驻资格只读端口（WBS-3.2.3 hifi §4 Q1-A：单一事实源在 subject-service，
 * 本服务不复制主体资格；实现 = infrastructure.SubjectAdmissionClient，沿 did SubjectStatusPort 先例）。
 */
public interface SubjectAdmissionPort {

    /** 查询主体是否已入驻（资格三态）。 */
    SubjectAdmission check(String subjectNo);
}
