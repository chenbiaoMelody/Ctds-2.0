package com.ctds.catalog.domain;

/**
 * 主体入驻资格只读端口（WBS-3.3.2 hifi §5；实现 = infrastructure.SubjectAdmissionClient，
 * 目标 = subject 内部端点 `GET /api/v1/subject/internal/subjects/{subjectNo}/admission`，
 * ADR-016 §6 衔接契约第 4 个消费方：主体本体 → did → space → catalog）。
 */
public interface SubjectAdmissionPort {

    /** 判定主体入驻资格（三态：ADMITTED / NOT_ADMITTED / UNAVAILABLE）。 */
    SubjectAdmission check(String subjectNo);
}
