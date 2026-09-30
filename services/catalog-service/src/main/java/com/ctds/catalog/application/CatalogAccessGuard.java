package com.ctds.catalog.application;

import com.ctds.common.auth.AuthAdvice;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.AuthException;
import com.ctds.common.errorcode.ErrorCodes;
import org.springframework.stereotype.Component;

/**
 * 目录域身份面（WBS-3.3.2 hifi §1：写面权限点经 {@code @RequirePermission} 注解静态门，
 * 身份判定另于应用服务双轨落定——资源管理动作的最终判定 = 登记主体本人，判定输入含数据行，
 * 非注解静态门可表达）。主体标识取 AuthContext（X-Ctds-Subject 演示期身份头口径）；
 * 未认证 = 401（沿 space SpaceAccessGuard 先例）。
 */
@Component
public class CatalogAccessGuard {

    /** 当前登录主体；未认证 = 401（AuthAdvice 精确映射，沿平台鉴权口径）。 */
    public String requireSubject() {
        final String subject = AuthContext.subject();
        if (subject == null) {
            throw new AuthException(ErrorCodes.UNAUTHORIZED, AuthAdvice.UNAUTHORIZED_MESSAGE);
        }
        return subject;
    }
}
