package com.ctds.connector.sdk;

import com.ctds.connector.sdk.capability.IdentityCapability;
import com.ctds.connector.sdk.model.DidStatusView;
import com.ctds.connector.sdk.model.SubjectRegistrationView;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;

/** 身份管理门面默认实现（薄封装平台客户端，零业务判定；路径变量 pathSegment 编码）。 */
final class DefaultIdentityCapability implements IdentityCapability {

    private final PlatformClient client;

    DefaultIdentityCapability(PlatformClient client) {
        this.client = client;
    }

    @Override
    public SubjectRegistrationView querySubject(String subjectNo) {
        requireText(subjectNo, "subjectNo");
        return client.get(
                uri -> uri.path("/api/v1/subject/registrations").pathSegment(subjectNo).build(),
                Map.of(),
                new ParameterizedTypeReference<PlatformResponse<SubjectRegistrationView>>() {
                });
    }

    @Override
    public DidStatusView resolveDid(String did) {
        requireText(did, "did");
        return client.get(
                uri -> uri.path("/api/v1/did").pathSegment(did).build(),
                Map.of(),
                new ParameterizedTypeReference<PlatformResponse<DidStatusView>>() {
                });
    }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ConnectorException(SdkErrorType.CONFIG, name + " 不可为空", null);
        }
    }
}
