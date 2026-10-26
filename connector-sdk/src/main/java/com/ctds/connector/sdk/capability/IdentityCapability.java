package com.ctds.connector.sdk.capability;

import com.ctds.connector.sdk.model.DidStatusView;
import com.ctds.connector.sdk.model.SubjectRegistrationView;

/**
 * 身份管理门面（规格行为 3）：查验<b>本所属主体</b>的身份信息与 DID 状态
 * （经平台主体 / DID 域只读消费）；不可查验其他主体——跨主体由平台侧拒绝并透传。
 */
public interface IdentityCapability {

    /** 查本主体登记详情（要素与入驻状态）。 */
    SubjectRegistrationView querySubject(String subjectNo);

    /** 查 DID 状态（可解析、状态有效——行为 3 标准 1）。 */
    DidStatusView resolveDid(String did);
}
