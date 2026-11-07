package com.ctds.evidence.application;

import com.ctds.common.auth.AccessControl;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import org.springframework.stereotype.Component;

/**
 * 审计读面访问守卫（WBS-3.8.4 hifi §5.1 / Q4-A；ADR-016 §2.6 与 subject OwnershipGuard 先例同型）：
 * 5 个审计端点不挂 @RequirePermission（避免 403 抢先），由本守卫在方法首行调
 * {@code AccessControl.require("evidence.governance")}——拒绝时 DefaultAccessControl 先落
 * rbac DENIED 审计（留痕自然发生），随后捕获 AuthException 转抛
 * <b>400 + 1000C0003 + "记录不存在"</b>，与"对象不存在"完全同码同文（防存在性枚举，
 * 剧本 S1-7 / S2-5 双向参照判定）。未认证（无身份头）同样落此口径。
 *
 * <p>P2 前置检查实证：AuthException extends BizException 可捕获；require 抛出前自动落 DENIED
 * 审计（DefaultAccessControl.java:44）；1000C0003 走 ErrorType.C → HTTP 400（全局默认映射，
 * EvidenceExceptionHandler default 分支 message 透传）。</p>
 */
@Component
public class EvidenceAuditAccessGuard {

    /** 审计读面统一"对象不存在"文案（越权 / 查无同码同文防枚举）。 */
    public static final String AUDIT_RECORD_NOT_FOUND_MESSAGE = "记录不存在";

    private final AccessControl accessControl;

    public EvidenceAuditAccessGuard(final AccessControl accessControl) {
        this.accessControl = accessControl;
    }

    /** 断言当前身份持 evidence.governance；否则统一"记录不存在"出站。 */
    public void requireGovernanceAccess() {
        try {
            accessControl.require("evidence.governance");
        } catch (final BizException denied) {
            // AuthException 仅携带 1000C0002/1000C0005 两鉴权码（构造即校验），到此即"非治理档"
            throw new BizException(ErrorCodes.RESOURCE_NOT_FOUND, AUDIT_RECORD_NOT_FOUND_MESSAGE);
        }
    }
}
