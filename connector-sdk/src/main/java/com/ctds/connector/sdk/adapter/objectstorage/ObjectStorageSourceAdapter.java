package com.ctds.connector.sdk.adapter.objectstorage;

import com.ctds.connector.sdk.adapter.gov.CredentialResolver;
import com.ctds.connector.sdk.spi.SourceAdapter;
import com.ctds.connector.sdk.spi.SourceCheckFailureType;
import com.ctds.connector.sdk.spi.SourceCheckResult;
import com.ctds.connector.sdk.spi.SourceReadRequest;
import com.ctds.connector.sdk.spi.SourceReadResult;
import com.ctds.connector.sdk.spi.SourceRegistration;
import com.ctds.connector.sdk.spi.SourceType;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 对象存储适配器（WBS-3.6.6；hifi §7.4）：访问凭据 = 提供方生成的预签名 URL，适配器只发 GET，
 * <b>不做签名实现</b>（红线：不自实现密码学）。
 *
 * <p>源地址（{@code https://…} / {@code s3://} / {@code oss://}）仅登记标识、不参与网络访问；
 * 自检对凭据 URL 带 {@code Range: bytes=0-0}，读取只取响应头（内容类型 / 大小），内容不落内存、
 * 摘要永不含对象内容与凭据原文。404 记"权限不足"（不泄露对象存在性）。</p>
 */
public final class ObjectStorageSourceAdapter implements SourceAdapter {

    private static final String MESSAGE_INVALID_CREDENTIAL = "凭据无效";
    private static final String MESSAGE_PERMISSION = "权限不足";
    private static final String MESSAGE_NETWORK = "网络不可达";
    private static final String READ_FAILURE_PREFIX = "读取失败：";
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final int STATUS_FORBIDDEN = 403;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int SUCCESS_MIN = 200;
    private static final int SUCCESS_MAX_EXCLUSIVE = 300;

    /** 403 应答体中"签名 / 凭证类"关键字（小写比对）。 */
    private static final Set<String> CREDENTIAL_KEYWORDS = Set.of(
            "signaturedoesnotmatch", "invalidaccesskeyid", "expiredtoken", "expired");

    private static final int SUMMARY_TEXT_MAX = 120;
    private static final String TEXT_NULL = "unknown";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    private final CredentialResolver credentialResolver;
    private final PresignedUrlClient client;

    public ObjectStorageSourceAdapter(final CredentialResolver credentialResolver) {
        this(credentialResolver, DEFAULT_TIMEOUT);
    }

    public ObjectStorageSourceAdapter(final CredentialResolver credentialResolver,
            final Duration timeout) {
        this.credentialResolver = credentialResolver;
        this.client = new PresignedUrlClient(timeout);
    }

    @Override
    public SourceType type() {
        return SourceType.OBJECT_STORAGE;
    }

    @Override
    public SourceCheckResult check(final SourceRegistration registration) {
        final Optional<ObjectStorageCredential> credential = credentialOf(registration);
        if (credential.isEmpty()) {
            return SourceCheckResult.fail(SourceCheckFailureType.INVALID_CREDENTIAL,
                    MESSAGE_INVALID_CREDENTIAL);
        }
        try {
            final PresignedUrlClient.Probe probe = client.probe(credential.get().url(), true);
            if (isSuccess(probe.status())) {
                return SourceCheckResult.ok();
            }
            final SourceCheckFailureType failureType = failureTypeOf(probe);
            if (failureType == null) {
                throw new IllegalStateException(
                        "对象存储探测应答异常（status=" + probe.status() + "）");
            }
            return SourceCheckResult.fail(failureType, messageOf(failureType));
        } catch (final IOException e) {
            return SourceCheckResult.fail(SourceCheckFailureType.NETWORK_UNREACHABLE, MESSAGE_NETWORK);
        }
    }

    @Override
    public SourceReadResult read(final SourceRegistration registration,
            final SourceReadRequest request) {
        final Optional<ObjectStorageCredential> credential = credentialOf(registration);
        if (credential.isEmpty()) {
            return new SourceReadResult(false, READ_FAILURE_PREFIX + MESSAGE_INVALID_CREDENTIAL);
        }
        try {
            final PresignedUrlClient.Probe probe = client.probe(credential.get().url(), false);
            if (isSuccess(probe.status())) {
                return new SourceReadResult(true, "contentType=" + sanitize(probe.contentType())
                        + ";size=" + sizeText(probe.contentLength()));
            }
            final SourceCheckFailureType failureType = failureTypeOf(probe);
            if (failureType == null) {
                throw new IllegalStateException(
                        "对象存储读取应答异常（status=" + probe.status() + "）");
            }
            return new SourceReadResult(false, READ_FAILURE_PREFIX + messageOf(failureType));
        } catch (final IOException e) {
            return new SourceReadResult(false, READ_FAILURE_PREFIX + MESSAGE_NETWORK);
        }
    }

    private Optional<ObjectStorageCredential> credentialOf(final SourceRegistration registration) {
        return ObjectStorageCredential.parse(credentialResolver.resolve(registration.credentialRef()));
    }

    private static boolean isSuccess(final int status) {
        return status >= SUCCESS_MIN && status < SUCCESS_MAX_EXCLUSIVE;
    }

    private static SourceCheckFailureType failureTypeOf(final PresignedUrlClient.Probe probe) {
        if (probe.status() == STATUS_UNAUTHORIZED) {
            return SourceCheckFailureType.INVALID_CREDENTIAL;
        }
        if (probe.status() == STATUS_FORBIDDEN) {
            return hasCredentialKeyword(probe.bodySnippet())
                    ? SourceCheckFailureType.INVALID_CREDENTIAL
                    : SourceCheckFailureType.INSUFFICIENT_PERMISSION;
        }
        if (probe.status() == STATUS_NOT_FOUND) {
            return SourceCheckFailureType.INSUFFICIENT_PERMISSION;
        }
        return null;
    }

    private static boolean hasCredentialKeyword(final String bodySnippet) {
        if (bodySnippet == null || bodySnippet.isEmpty()) {
            return false;
        }
        final String text = bodySnippet.toLowerCase(Locale.ROOT);
        return CREDENTIAL_KEYWORDS.stream().anyMatch(text::contains);
    }

    private static String messageOf(final SourceCheckFailureType failureType) {
        return switch (failureType) {
            case INVALID_CREDENTIAL -> MESSAGE_INVALID_CREDENTIAL;
            case INSUFFICIENT_PERMISSION -> MESSAGE_PERMISSION;
            case NETWORK_UNREACHABLE -> MESSAGE_NETWORK;
        };
    }

    /**
     * 源端可控文本收敛（评审循环 1 ③P2，沿 gov 适配器 sanitize 口径）：去控制字符、{@code ;} 转空格
     * （留痕 detail 的字段分隔符）、限长 120；摘要不得成为伪造留痕字段或注入控制字符的通道。
     */
    private static String sanitize(final String value) {
        if (value == null || value.isBlank()) {
            return TEXT_NULL;
        }
        String text = value.replaceAll("\\p{Cntrl}", " ").replace(';', ' ').trim();
        if (text.length() > SUMMARY_TEXT_MAX) {
            text = text.substring(0, SUMMARY_TEXT_MAX);
        }
        return text;
    }

    private static String sizeText(final long contentLength) {
        return contentLength < 0 ? "unknown" : String.valueOf(contentLength);
    }
}
