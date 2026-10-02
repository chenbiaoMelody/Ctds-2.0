package com.ctds.catalog.application;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import com.ctds.common.auth.AuthAdvice;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.AuthException;
import com.ctds.common.errorcode.ErrorCodes;
import org.springframework.stereotype.Component;

/**
 * 目录域身份与资格门槛面（WBS-3.3.2 hifi §1：写面权限点经 {@code @RequirePermission} 注解静态门，
 * 身份判定另于应用服务双轨落定——资源管理动作的最终判定 = 登记主体本人，判定输入含数据行，
 * 非注解静态门可表达）。主体标识取 AuthContext（X-Ctds-Subject 演示期身份头口径）；
 * 未认证 = 401（沿 space SpaceAccessGuard 先例）。
 *
 * <p>ADMITTED 资格门槛（WBS-3.3.4 R6/R7/R8 与 W4/W6 共用）收敛于本类单点：同一拒绝码同文案，
 * 防多副本漂移。登记路径（W1）同族门槛因语境文案不同（ADMISSION_REQUIRED_MESSAGE）另于
 * {@code DatasetRegistrationService} 单列，不复用本方法。</p>
 */
@Component
public class CatalogAccessGuard {

    private final SubjectAdmissionPort admissionPort;

    public CatalogAccessGuard(final SubjectAdmissionPort admissionPort) {
        this.admissionPort = admissionPort;
    }

    /** 当前登录主体；未认证 = 401（AuthAdvice 精确映射，沿平台鉴权口径）。 */
    public String requireSubject() {
        final String subject = AuthContext.subject();
        if (subject == null) {
            throw new AuthException(ErrorCodes.UNAUTHORIZED, AuthAdvice.UNAUTHORIZED_MESSAGE);
        }
        return subject;
    }

    /**
     * ADMITTED 资格门槛（Q8-A 复用既有通道）：未入驻 → 1007C0006 统一文案（防枚举，零留痕零副作用）；
     * 主体服务不可用 → 1007S0001（不冒充资格拒绝）。
     */
    public void requireAdmitted(final String subject) {
        final SubjectAdmission admission = admissionPort.check(subject);
        if (admission == SubjectAdmission.NOT_ADMITTED) {
            throw new CatalogBizException(CatalogErrorCodes.DATASET_FORBIDDEN,
                    CatalogErrorCodes.CATALOG_ADMISSION_REQUIRED_MESSAGE);
        }
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new CatalogBizException(CatalogErrorCodes.SUBJECT_SERVICE_UNAVAILABLE,
                    CatalogErrorCodes.SUBJECT_SERVICE_UNAVAILABLE_MESSAGE);
        }
    }
}
