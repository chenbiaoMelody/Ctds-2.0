package com.ctds.did.domain;

/**
 * 主体状态只读端口（WBS-3.1.9 hifi §5）：单一事实源在 subject-service（ADR-017 §2.2），
 * 本服务不复制主体状态到 DID 库；服务不可达/响应异常 → UNAVAILABLE。
 */
public interface SubjectStatusPort {

    /** 查询主体是否已入驻（绑定核验三态）。 */
    SubjectAdmission check(String subjectNo);
}