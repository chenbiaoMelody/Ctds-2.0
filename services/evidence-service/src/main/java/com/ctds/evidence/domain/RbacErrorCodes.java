package com.ctds.evidence.domain;

import com.ctds.common.errorcode.ErrorCode;

/**
 * WBS-3.9.2 Q7-A：1013 段最小码值集（仅本包所需的授权 / 映射语义）。
 * 401 / 403 仍走 common-auth 公共码，由统一异常出口映射。
 */
public final class RbacErrorCodes {

    /** 入参不合法（角色 / 权限点 / 变更单号为空或超长）。 */
    public static final ErrorCode PARAM_INVALID = ErrorCode.of("1013C0001");
    public static final String PARAM_INVALID_MESSAGE = "请求参数不合法，请检查后重试";

    /** 角色或权限点不在登记值域内（新增须走变更流程，禁止顺手新增）。 */
    public static final ErrorCode UNREGISTERED = ErrorCode.of("1013C0002");
    public static final String UNREGISTERED_MESSAGE = "角色或权限点未登记，请走变更流程";

    /** 收回尚未授予的权限点：零副作用拒绝，不产生留痕。 */
    public static final ErrorCode REVOKE_NOT_GRANTED = ErrorCode.of("1013C0003");
    public static final String REVOKE_NOT_GRANTED_MESSAGE = "该权限点尚未授予，无需收回";

    /** 映射库不可达（fail-closed 场景的可读业务语义，不暴露库表结构）。 */
    public static final ErrorCode MAPPING_UNAVAILABLE = ErrorCode.of("1013S0001");
    public static final String MAPPING_UNAVAILABLE_MESSAGE = "权限映射暂不可用，请稍后重试";

    private RbacErrorCodes() {
    }
}
