package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.IdentityCapability;
import com.ctds.connector.sdk.model.DidStatusView;
import com.ctds.connector.sdk.model.SubjectRegistrationView;
import org.springframework.core.ParameterizedTypeReference;

/**
 * 身份管理门面默认实现（薄封装平台客户端，零业务判定）。
 *
 * <p>包级私有：实现类不对外暴露（对外 API 面 = 六接口 + 入口 + 模型 + SPI）。</p>
 */
final class DefaultIdentityCapability implements IdentityCapability {

    private final PlatformClient client;

    DefaultIdentityCapability(PlatformClient client) {
        this.client = client;
    }

    @Override
    public SubjectRegistrationView querySubject(String subjectNo) {
        requireText(subjectNo, "subjectNo");
        return client.get("/api/v1/subject/registrations/" + subjectNo,
                new ParameterizedTypeReference<PlatformResponse<SubjectRegistrationView>>() {
                });
    }

    @Override
    public DidStatusView resolveDid(String did) {
        requireText(did, "did");
        return client.get("/api/v1/did/" + did,
                new ParameterizedTypeReference<PlatformResponse<DidStatusView>>() {
                });
    }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ConnectorException(SdkErrorType.CONFIG, name + " 不可为空", null);
        }
    }
}
