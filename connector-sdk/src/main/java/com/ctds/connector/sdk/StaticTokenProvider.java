package com.ctds.connector.sdk;

/**
 * 静态令牌实现（演示期口径；刷新型随 3.9.1 演进）。
 */
public final class StaticTokenProvider implements TokenProvider {

    private final String token;

    public StaticTokenProvider(String token) {
        if (token == null || token.isBlank()) {
            throw new ConnectorException(SdkErrorType.CONFIG, "令牌不可为空", null);
        }
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }
}
