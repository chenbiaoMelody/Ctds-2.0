package com.ctds.delivery.infrastructure;

import com.ctds.delivery.config.DeliveryProperties;
import com.ctds.delivery.domain.CatalogPort;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 目录域内部只读衔接客户端（hifi §4.3；JDK HttpClient 零新依赖）：
 * 读取产品形态；不存在/未知形态/不可用一律 fail-closed（1009S0002，不冒充形态判定）。
 */
@Component
public class CatalogInternalClient implements CatalogPort {

    private static final Logger log = LoggerFactory.getLogger(CatalogInternalClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final String INTERNAL_SUBJECT = "delivery-service";
    private static final String INTERNAL_ROLES = "delivery-internal";

    private final DeliveryProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CatalogInternalClient(final DeliveryProperties properties, final ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public String productType(final long productId) {
        final String base = properties.getCatalog().getBaseUrl();
        if (base == null || base.isBlank()) {
            throw unavailable();
        }
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/v1/catalog/internal/data-products/" + productId))
                .timeout(properties.getCatalog().getTimeout())
                .header("X-Ctds-Subject", INTERNAL_SUBJECT).header("X-Ctds-Roles", INTERNAL_ROLES)
                .GET().build();
        final HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            log.warn("目录服务不可达: productId={}", productId, e);
            throw unavailable();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("目录服务调用被中断: productId={}", productId);
            throw unavailable();
        } catch (final RuntimeException e) {
            log.warn("目录服务请求构造失败: productId={}", productId, e);
            throw unavailable();
        }
        final JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (final IOException e) {
            log.warn("目录响应解析失败: productId={}, status={}", productId, response.statusCode(), e);
            throw unavailable();
        }
        final String type = body.path("data").path("productType").asText("");
        if (response.statusCode() != 200 || !"0".equals(body.path("code").asText()) || type.isBlank()) {
            log.warn("目录产品形态不可用: productId={}, status={}, code={}", productId,
                    response.statusCode(), body.path("code").asText());
            throw unavailable();
        }
        return type;
    }

    private static DeliveryBizException unavailable() {
        return new DeliveryBizException(DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE,
                DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE);
    }
}
