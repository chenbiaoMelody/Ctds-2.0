package com.ctds.common.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KMS 托管密钥供给（WBS-2.6.3，ADR-006 §5.1 覆盖式接入：业务代码零改动）。
 * 经 HTTP 调用平台内置 KMS 服务（services/kms）取密钥材料；配置 {@code ctds.crypto.kms.base-url} 即启用。
 * 任何失败（不可达/超时/编号或版本不存在/响应不合法/材料长度不对）一律收敛
 * {@code 1001S0001} 密钥服务暂不可用——fail-fast 不降级（规格 C-2.6.3 行为 1）。
 * 红线：密钥材料只进内存，禁入日志与异常消息（本类日志只含编号/版本/结果类别）。
 */
public final class KmsKeyProvider implements VersionedKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(KmsKeyProvider.class);
    private static final int SM4_KEY_BYTES = 16;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration readTimeout;

    public KmsKeyProvider(final String baseUrl, final Duration connectTimeout, final Duration readTimeout) {
        this.baseUrl = URI.create(trimTrailingSlash(baseUrl));
        this.readTimeout = readTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    private static String trimTrailingSlash(final String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public int currentVersion(final String keyRef) {
        Inputs.requireKeyRef(keyRef);
        final JsonNode data = callForData("/api/v1/keys/" + keyRef, keyRef, -1);
        final JsonNode version = data.get("currentVersion");
        if (version == null || !version.canConvertToInt() || version.asInt() < 1) {
            log.warn("KMS current version response invalid: keyRef={}", keyRef);
            throw Inputs.keyUnavailable();
        }
        return version.asInt();
    }

    @Override
    public byte[] sm4Key(final String keyRef, final int version) {
        Inputs.requireKeyRef(keyRef);
        return materialOf(callForData("/api/v1/keys/" + keyRef + "/versions/" + version + "/material", keyRef, version),
                keyRef, version);
    }

    @Override
    public byte[] sm4Key(final String keyRef) {
        Inputs.requireKeyRef(keyRef);
        return materialOf(callForData("/api/v1/keys/" + keyRef + "/material", keyRef, -1), keyRef, -1);
    }

    /** 统一 HTTP 调用 + ApiResult 封套解析；任何失败收敛 1001S0001（错误原因只记类别，不记响应内容）。 */
    private JsonNode callForData(final String path, final String keyRef, final int version) {
        final HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(readTimeout)
                .GET()
                .build();
        final HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException e) {
            log.warn("KMS unreachable: keyRef={} action=current-material", keyRef);
            throw Inputs.keyUnavailable();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("KMS call interrupted: keyRef={}", keyRef);
            throw Inputs.keyUnavailable();
        }
        if (response.statusCode() != 200) {
            log.warn("KMS responded non-200: keyRef={} version={} status={}", keyRef, version, response.statusCode());
            throw Inputs.keyUnavailable();
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        } catch (final IOException e) {
            log.warn("KMS response not parseable: keyRef={} version={}", keyRef, version);
            throw Inputs.keyUnavailable();
        }
        final JsonNode code = root.get("code");
        if (code == null || !"0".equals(code.asText())) {
            log.warn("KMS returned business error: keyRef={} version={}", keyRef, version);
            throw Inputs.keyUnavailable();
        }
        final JsonNode data = root.get("data");
        if (data == null || data.isNull() || !data.isObject()) {
            log.warn("KMS response data missing: keyRef={} version={}", keyRef, version);
            throw Inputs.keyUnavailable();
        }
        return data;
    }

    private static byte[] materialOf(final JsonNode data, final String keyRef, final int version) {
        final JsonNode material = data.get("material");
        if (material == null || material.isNull() || material.asText().isBlank()) {
            log.warn("KMS material field missing: keyRef={} version={}", keyRef, version);
            throw Inputs.keyUnavailable();
        }
        final byte[] key;
        try {
            key = java.util.Base64.getDecoder().decode(material.asText());
        } catch (final IllegalArgumentException e) {
            log.warn("KMS material not base64: keyRef={} version={}", keyRef, version);
            throw Inputs.keyUnavailable();
        }
        if (key.length != SM4_KEY_BYTES) {
            log.warn("KMS material length invalid: keyRef={} version={}", keyRef, version);
            throw Inputs.keyUnavailable();
        }
        return key;
    }
}
