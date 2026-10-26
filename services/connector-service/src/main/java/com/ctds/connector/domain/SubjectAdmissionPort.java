package com.ctds.connector.domain;

/**
 * 主体入驻资格只读端口（单一事实源在主体域，ADR-016）。
 */
public interface SubjectAdmissionPort {

    SubjectAdmission check(String subjectNo);
}
