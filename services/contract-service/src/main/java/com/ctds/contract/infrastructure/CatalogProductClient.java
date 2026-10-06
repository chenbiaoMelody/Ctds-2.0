package com.ctds.contract.infrastructure;

import com.ctds.contract.domain.CatalogProductPort;
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
 * 合约 → 目录服务 产品事实只读客户端（WBS-3.4.3 hifi §9；JDK HttpClient 零新增依赖，
 * 沿 SubjectAdmissionClient 先例——本副本为跨服务只读 client 第 5 份，收敛候选登记
 * ADR-010 §10，跟踪-7 计数 +1）。单一事实源在目录服务（ADR-016 §6 衔接契约第 6 消费方）：
 * 内部只读端点最小暴露 6 字段（Q6-A）。服务身份头 contract-service / contract-internal
 * （catalog yml 1 行授权 catalog.internal.read）。
 * 未配置 base-url = 产品事实不可用（服务可独立启动）；产品不存在业务答复（1007C0011）=
 * NOT_FOUND；其余任何失败（不可达/超时/非 200/非 0 码/解析失败/请求构造失败）→ UNAVAILABLE，
 * 不冒充产品状态（防枚举口径，hifi §3 1008C0010/1008S0003 分工）。
 */
@Component
public class CatalogProductClient implements CatalogProductPort {

    private static final Logger log = LoggerFactory.getLogger(CatalogProductClient.class);
    /** 连接超时（沿 catalog 契约：connectTimeout 2s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** 读超时（沿 catalog 契约：readTimeout 3s）。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String INTERNAL_SUBJECT = "contract-service";
    private static final String INTERNAL_ROLES = "contract-internal";
    /** 产品不存在/未在架业务码（1007C0011——内部端点复用，零新增 catalog 码）。 */
    private static final String PRODUCT_NOT_FOUND_CODE = "1007C0011";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CatalogProductClient(@Value("${ctds.contract.catalog.base-url:}") final String baseUrl,
            final ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public CatalogProductResult fetch(final long productId) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("ctds.contract.catalog.base-url 未配置，产品事实不可用: productId={}", productId);
            return CatalogProductResult.unavailable();
        }
        final HttpResponse<String> response;
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/catalog/internal/data-products/" + productId))
                    .timeout(READ_TIMEOUT)
                    .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                    .header("X-Ctds-Roles", INTERNAL_ROLES)
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException e) {
            log.warn("目录服务不可达，产品事实不可用: productId={}", productId, e);
            return CatalogProductResult.unavailable();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("目录服务调用被中断，产品事实不可用: productId={}", productId);
            return CatalogProductResult.unavailable();
        } catch (final RuntimeException e) {
            // 请求构造失败同归 UNAVAILABLE：fail-closed 且不冒充产品状态
            log.warn("目录服务请求构造失败，产品事实不可用: productId={}", productId, e);
            return CatalogProductResult.unavailable();
        }
        final JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (final JsonProcessingException e) {
            log.warn("目录服务响应解析失败，产品事实不可用: productId={}, status={}", productId,
                    response.statusCode(), e);
            return CatalogProductResult.unavailable();
        }
        final String code = body.path("code").asText();
        // 产品不存在（业务答复 1007C0011）= NOT_FOUND（不存在/未在架同形——防枚举）
        if (PRODUCT_NOT_FOUND_CODE.equals(code)) {
            return CatalogProductResult.notFound();
        }
        // 非 200 或业务码非 0：一律如实归"不可用"（不把系统态当作产品事实）
        if (response.statusCode() != 200 || !"0".equals(code)) {
            log.warn("目录服务响应非正常，产品事实不可用: productId={}, status={}, code={}", productId,
                    response.statusCode(), code);
            return CatalogProductResult.unavailable();
        }
        final JsonNode data = body.path("data");
        return CatalogProductResult.found(new CatalogProduct(
                data.path("productId").asLong(productId),
                data.path("productName").asText(null),
                data.path("status").asText(null),
                data.path("providerSubjectNo").asText(null),
                data.path("pricingModel").asText(null),
                data.hasNonNull("priceAmount") ? data.path("priceAmount").decimalValue() : null));
    }
}
