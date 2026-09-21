package com.ctds.subject.application;

import com.ctds.subject.domain.DidIssuancePort;
import com.ctds.subject.domain.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DID 签发触发器（WBS-3.1.8 hifi §4.3）：审核通过事务提交后调用，失败仅记 WARN、不抛出、
 * 不影响批准响应与入驻状态（行为 1 规则 5）。
 */
@Component
public class DidIssuanceTrigger {

    private static final Logger log = LoggerFactory.getLogger(DidIssuanceTrigger.class);

    private final DidIssuancePort port;

    public DidIssuanceTrigger(final DidIssuancePort port) {
        this.port = port;
    }

    /** 主体已入驻后的签发触发（事务提交后调用）。 */
    public void afterAdmitted(final Subject subject) {
        try {
            port.triggerIssuance(subject.subjectNo(), subject.subjectName(), subject.subjectType().name());
        } catch (final RuntimeException e) {
            log.warn("DID 签发触发失败（不影响审核结论）: subjectNo={}", subject.subjectNo(), e);
        }
    }
}
