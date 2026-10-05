package com.ctds.contract.domain;

/**
 * 主体资格判定端口（六边形端口，沿 catalog SubjectAdmissionPort 先例；
 * 实现 = infrastructure.SubjectAdmissionClient，ADR-016 §6 衔接契约第 5 消费方）。
 */
public interface SubjectAdmissionPort {

    /** 判定主体资格（三态；只读不缓存——单一事实源在主体服务）。 */
    SubjectAdmission check(String subjectNo);
}
