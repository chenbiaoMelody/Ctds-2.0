package com.ctds.connector.sdk.adapter.apigateway;

import com.ctds.connector.sdk.adapter.gov.CredentialResolver;
import com.ctds.connector.sdk.spi.SourceAdapter;
import com.ctds.connector.sdk.spi.SourceCheckFailureType;
import com.ctds.connector.sdk.spi.SourceCheckResult;
import com.ctds.connector.sdk.spi.SourceReadRequest;
import com.ctds.connector.sdk.spi.SourceReadResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import java.io.IOException;
import java.util.Optional;

/**
 * API 网关适配器（WBS-3.6.6；hifi §7.5）：GET 只读，凭据为完整 {@code Authorization} 头值，
 * 原样出站（不解析 / 不拼接 / 不改写）。
 *
 * <p>摘要只含状态码 / 字节数 / 内容类型，<b>永不返回响应内容</b>；404 及其它非 2xx 不猜测语义，
 * 统一上抛（平台侧 502 中性文案）。凭据含控制字符（头注入）即判"凭据无效"，不发起请求。</p>
 */
public final class ApiGatewaySourceAdapter implements SourceAdapter {

    private static final String MESSAGE_INVALID_CREDENTIAL = "凭据无效";
    private static final String MESSAGE_PERMISSION = "权限不足";
    private static final String MESSAGE_NETWORK = "网络不可达";
    private static final String READ_FAILURE_PREFIX = "读取失败：";
    private static final int MAX_CREDENTIAL_LENGTH = 1024;
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final int STATUS_FORBIDDEN = 403;
    private static final int SUCCESS_MIN = 200;
    private static final int SUCCESS_MAX_EXCLUSIVE = 300;

    private final CredentialResolver credentialResolver;
    private final ApiGatewayClient client = new ApiGatewayClient();

    public ApiGatewaySourceAdapter(final CredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    @Override
    public SourceType type() {
        return SourceType.API_GATEWAY;
    }

    @Override
    public SourceCheckResult check(final SourceRegistration registration) {
        final Optional<String> authorization = credentialOf(registration);
        if (authorization.isEmpty()) {
            return SourceCheckResult.fail(SourceCheckFailureType.INVALID_CREDENTIAL,
                    MESSAGE_INVALID_CREDENTIAL);
        }
        try {
            final HttpProbeResult result =
                    client.probe(registration.endpoint(), authorization.get(), false);
            if (isSuccess(result.status())) {
                return SourceCheckResult.ok();
            }
            final SourceCheckFailureType failureType = failureTypeOf(result.status());
            if (failureType == null) {
                throw new IllegalStateException(
                        "API 网关探测应答异常（status=" + result.status() + "）");
            }
            return SourceCheckResult.fail(failureType, messageOf(failureType));
        } catch (final IOException e) {
            return SourceCheckResult.fail(SourceCheckFailureType.NETWORK_UNREACHABLE, MESSAGE_NETWORK);
        }
    }

    @Override
    public SourceReadResult read(final SourceRegistration registration,
            final SourceReadRequest request) {
        final Optional<String> authorization = credentialOf(registration);
        if (authorization.isEmpty()) {
            return new SourceReadResult(false, READ_FAILURE_PREFIX + MESSAGE_INVALID_CREDENTIAL);
        }
        try {
            final HttpProbeResult result =
                    client.probe(registration.endpoint(), authorization.get(), true);
            if (isSuccess(result.status())) {
                return new SourceReadResult(true, "status=" + result.status() + ";bytes="
                        + result.bytes() + ";contentType=" + result.contentType());
            }
            final SourceCheckFailureType failureType = failureTypeOf(result.status());
            if (failureType == null) {
                throw new IllegalStateException(
                        "API 网关读取应答异常（status=" + result.status() + "）");
            }
            return new SourceReadResult(false, READ_FAILURE_PREFIX + messageOf(failureType));
        } catch (final IOException e) {
            return new SourceReadResult(false, READ_FAILURE_PREFIX + MESSAGE_NETWORK);
        }
    }

    /** 凭据形态：非空、≤1024、无控制字符（HTTP 头注入防护）。 */
    private Optional<String> credentialOf(final SourceRegistration registration) {
        final String plaintext = credentialResolver.resolve(registration.credentialRef());
        if (plaintext == null || plaintext.isBlank() || plaintext.length() > MAX_CREDENTIAL_LENGTH) {
            return Optional.empty();
        }
        for (int index = 0; index < plaintext.length(); index++) {
            if (Character.isISOControl(plaintext.charAt(index))) {
                return Optional.empty();
            }
        }
        return Optional.of(plaintext);
    }

    private static boolean isSuccess(final int status) {
        return status >= SUCCESS_MIN && status < SUCCESS_MAX_EXCLUSIVE;
    }

    private static SourceCheckFailureType failureTypeOf(final int status) {
        if (status == STATUS_UNAUTHORIZED) {
            return SourceCheckFailureType.INVALID_CREDENTIAL;
        }
        if (status == STATUS_FORBIDDEN) {
            return SourceCheckFailureType.INSUFFICIENT_PERMISSION;
        }
        return null;
    }

    private static String messageOf(final SourceCheckFailureType failureType) {
        return switch (failureType) {
            case INVALID_CREDENTIAL -> MESSAGE_INVALID_CREDENTIAL;
            case INSUFFICIENT_PERMISSION -> MESSAGE_PERMISSION;
            case NETWORK_UNREACHABLE -> MESSAGE_NETWORK;
        };
    }
}
