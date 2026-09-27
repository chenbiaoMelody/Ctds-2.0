package com.ctds.space.application;

import com.ctds.space.domain.SpaceBizException;
import com.ctds.space.domain.SpaceErrorCodes;
import com.ctds.space.domain.SubjectAdmission;
import com.ctds.space.domain.SubjectAdmissionPort;
import org.springframework.stereotype.Component;

/**
 * 资格门槛三态判定（行为 1 规则 1 资格门槛 + 行为 2 规则 2 启用前提复查）：创建与生命周期
 * 共用单一实现（评审循环 1 一致性收敛——防枚举与"不可用"口径不分叉）。三态严格分离：
 * ADMITTED 放行；NOT_ADMITTED → 1006C0001 统一文案（不区分"主体不存在/未入驻"）；
 * UNAVAILABLE → 1006S0001（不冒充资格拒绝，hifi §4）。
 */
@Component
class SubjectAdmissionGate {

    private final SubjectAdmissionPort admissionPort;

    SubjectAdmissionGate(final SubjectAdmissionPort admissionPort) {
        this.admissionPort = admissionPort;
    }

    void requireAdmitted(final String subjectNo) {
        final SubjectAdmission admission = admissionPort.check(subjectNo);
        if (admission == SubjectAdmission.NOT_ADMITTED) {
            throw new SpaceBizException(SpaceErrorCodes.ADMISSION_REQUIRED,
                    SpaceErrorCodes.ADMISSION_REQUIRED_MESSAGE);
        }
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new SpaceBizException(SpaceErrorCodes.SUBJECT_SERVICE_UNAVAILABLE,
                    SpaceErrorCodes.SUBJECT_SERVICE_UNAVAILABLE_MESSAGE);
        }
    }
}
