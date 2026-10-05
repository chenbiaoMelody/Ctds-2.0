package com.ctds.contract.infrastructure;

import com.ctds.contract.domain.DidPort;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 合约 → DID 服务 客户端（WBS-3.4.3 hifi §6.4/§9；JDK HttpClient 零新增依赖，沿
 * SubjectAdmissionClient 先例——本副本为跨服务只读 client 第 6 份，跟踪-7 计数 +1）。
 * 三能力（Q5-A 复用 did 既有端点）：解析 GET /api/v1/did/{did}（公开无鉴权：文档 controller =
 * 主体编号 + 状态）、代签 POST /api/v1/did/{did}/demo-signatures（演示签名入口，服务身份头
 * contract-service / contract-internal 专用权限点 did.demo.signature；入口默认关闭——关闭
 * 答复 1000C0003 按 UNAVAILABLE 承载，不冒充签署能力）、验签 POST /api/v1/did/{did}/verifications
 * （公开无鉴权：三查结论）。
 * fail-closed 口径：解析未登记业务答复（1005B0003，随非 200 出站）= NOT_REGISTERED（签署身份
 * 不成立 → 应用层 C0018，业务码优先于 HTTP 状态判定——沿 SubjectAdmissionClient 口径）；
 * 其余任何失败 → UNAVAILABLE（→ S0002，不冒充签署结论）。验签区分 did 服务侧明确答复
 * （UNAVAILABLE + 原因）与传输层失败（transportFailure——R9 整体 503 承载）。
 */
@Component
public class DidClient implements DidPort {

    private static final Logger log = LoggerFactory.getLogger(DidClient.class);
    /** 连接超时（沿 catalog 契约：connectTimeout 2s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** 读超时（沿 catalog 契约：readTimeout 3s）。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String INTERNAL_SUBJECT = "contract-service";
    private static final String INTERNAL_ROLES = "contract-internal";
    /** DID 未登记业务码（1005B0003——解析答复，签署身份不成立）。 */
    private static final String DID_NOT_REGISTERED_CODE = "1005B0003";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DidClient(@Value("${ctds.contract.did.base-url:}") final String baseUrl,
            final ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public DidBinding resolve(final String did) {
        final Answer answer = exchange("/api/v1/did/" + did, did, null);
        if (answer == null) {
            return DidBinding.unavailable();
        }
        final String code = answer.body().path("code").asText();
        // 未登记业务答复 = NOT_REGISTERED（签署身份不成立 → 应用层 C0018）
        if (DID_NOT_REGISTERED_CODE.equals(code)) {
            return DidBinding.notRegistered();
        }
        if (!answer.ok() || !"0".equals(code)) {
            return DidBinding.unavailable();
        }
        final JsonNode data = answer.body().path("data");
        return DidBinding.found(data.path("document").path("controller").asText(null),
                data.path("status").asText(null));
    }

    @Override
    public DidSignResult sign(final String did, final String contentHash) {
        final Answer answer = exchange("/api/v1/did/" + did + "/demo-signatures", did,
                objectMapper.createObjectNode().put("data", contentHash));
        if (answer == null || !answer.ok()) {
            // 入口关闭（1000C0003）/未完成签发/不可达等一律 UNAVAILABLE：fail-closed 不冒充签署能力
            return DidSignResult.unavailable();
        }
        final String signature = answer.body().path("data").path("signature").asText(null);
        return signature == null || signature.isBlank()
                ? DidSignResult.unavailable()
                : DidSignResult.signed(signature);
    }

    @Override
    public DidVerifyResult verify(final String did, final String dataBase64,
            final String signatureBase64) {
        final JsonNode payload = objectMapper.createObjectNode()
                .put("data", dataBase64).put("signature", signatureBase64);
        final Answer answer = exchange("/api/v1/did/" + did + "/verifications", did, payload);
        if (answer == null || !answer.ok()) {
            // 输入参数类答复（1005Cxxxx）= 我方载荷缺陷或传输异常：fail-closed 不冒充结论
            return DidVerifyResult.transportUnreachable();
        }
        return DidVerifyResult.of(
                DidVerifyResult.Outcome.valueOf(answer.body().path("data").path("result").asText()),
                answer.body().path("data").path("reason").asText(null));
    }

    /** 答复载体（body = 已解析 JSON；ok = HTTP 200 且业务码 0）。 */
    private record Answer(boolean ok, JsonNode body) {
    }

    /**
     * 通用交换（GET/POST；一律携服务身份头——代签为服务间专用权限点，解析/验签公开无鉴权
     * 带身份头无副作用）：传输/解析/请求构造失败返回 null（UNAVAILABLE 语义）；业务答复
     * （含非 200 的业务码答复）原样返回由调用方判定语义。
     */
    private Answer exchange(final String path, final String did, final JsonNode payload) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("ctds.contract.did.base-url 未配置，DID 能力不可用: path={}", path);
            return null;
        }
        final HttpResponse<String> response;
        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(READ_TIMEOUT)
                    .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                    .header("X-Ctds-Roles", INTERNAL_ROLES);
            if (payload == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
            }
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("DID 服务调用被中断: did={}, path={}", did, path);
            return null;
        } catch (final IOException | RuntimeException e) {
            log.warn("DID 服务调用失败（UNAVAILABLE 语义）: did={}, path={}", did, path, e);
            return null;
        }
        try {
            final JsonNode body = objectMapper.readTree(response.body());
            final boolean ok = response.statusCode() == 200
                    && "0".equals(body.path("code").asText());
            if (!ok) {
                log.warn("DID 服务响应非正常: did={}, path={}, status={}, code={}", did, path,
                        response.statusCode(), body.path("code").asText());
            }
            return new Answer(ok, body);
        } catch (final JsonProcessingException | RuntimeException e) {
            log.warn("DID 服务响应解析失败: did={}, path={}, status={}", did, path,
                    response.statusCode(), e);
            return null;
        }
    }
}
