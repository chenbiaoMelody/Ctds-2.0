package com.ctds.delivery.infrastructure;

import com.ctds.delivery.config.DeliveryProperties;
import com.ctds.delivery.domain.ContractPassThroughException;
import com.ctds.delivery.domain.ContractPort;
import com.ctds.delivery.domain.ContractSummary;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 合约域内部只读衔接客户端（hifi §4.3；JDK HttpClient 零新依赖，沿 contract CatalogProductClient 先例）。
 * 只消费摘要/判定结果；1008C 型结论原码透传，其余失败一律 fail-closed（1009S0002）。
 */
@Component
public class ContractInternalClient implements ContractPort {

    private static final Logger log = LoggerFactory.getLogger(ContractInternalClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final String INTERNAL_SUBJECT = "delivery-service";
    private static final String INTERNAL_ROLES = "delivery-internal";
    private static final Set<String> PASS_THROUGH_CODES =
            Set.of("1008C0008", "1008C0012", "1008C0013", "1008C0020");

    private final DeliveryProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ContractInternalClient(final DeliveryProperties properties, final ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public ContractSummary summarize(final String contractNo, final String requesterNo) {
        final String base = properties.getContract().getBaseUrl();
        if (base == null || base.isBlank()) {
            throw unavailable();
        }
        final String url = base + "/api/v1/contracts/internal/contract-summaries/"
                + encodePath(contractNo) + "?requesterNo="
                + URLEncoder.encode(requesterNo, StandardCharsets.UTF_8);
        final JsonNode data = get(url, properties.getContract().getTimeout(), "合约摘要");
        final String summaryContractNo = requireSummaryText(data, "contractNo", contractNo);
        if (!contractNo.equals(summaryContractNo)) {
            log.warn("合约摘要编号不匹配: requested={}", contractNo);
            throw unavailable();
        }
        final long productId = data.path("productId").asLong(0);
        if (productId <= 0) {
            log.warn("合约摘要产品缺失: contractNo={}", contractNo);
            throw unavailable();
        }
        final String effectiveAtText = data.path("effectiveAt").isNull() ? null
                : data.path("effectiveAt").asText(null);
        LocalDateTime effectiveAt = null;
        if (effectiveAtText != null && !effectiveAtText.isBlank()) {
            try {
                effectiveAt = LocalDateTime.parse(effectiveAtText);
            } catch (final DateTimeParseException e) {
                log.warn("合约摘要 effectiveAt 解析失败: contractNo={}", contractNo);
                throw unavailable();
            }
        }
        return new ContractSummary(summaryContractNo, productId,
                requireSummaryText(data, "productName", contractNo),
                requireSummaryText(data, "applicantNo", contractNo),
                requireSummaryText(data, "providerNo", contractNo),
                requireSummaryText(data, "status", contractNo), effectiveAt);
    }

    private static String requireSummaryText(final JsonNode data, final String field,
            final String contractNo) {
        final String value = data.path(field).asText("");
        if (value.isBlank()) {
            log.warn("合约摘要字段缺失: contractNo={}, field={}", contractNo, field);
            throw unavailable();
        }
        return value;
    }

    @Override
    public void check(final String contractNo, final String requesterNo, final String purpose,
            final String territory) {
        final String base = properties.getContract().getBaseUrl();
        if (base == null || base.isBlank()) {
            throw unavailable();
        }
        final String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "contractNo", contractNo,
                    "requesterNo", requesterNo,
                    "actionType", "USE",
                    "purpose", purpose == null ? "" : purpose,
                    "territory", territory == null ? "" : territory));
        } catch (final IOException e) {
            throw unavailable();
        }
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/v1/contracts/internal/usage-checks"))
                .timeout(properties.getContract().getTimeout())
                .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                .header("X-Ctds-Roles", INTERNAL_ROLES)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        parse(send(request, "合约判定"), true);
    }

    private JsonNode get(final String url, final Duration timeout, final String action) {
        final HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout)
                .header("X-Ctds-Subject", INTERNAL_SUBJECT).header("X-Ctds-Roles", INTERNAL_ROLES)
                .GET().build();
        return parse(send(request, action), false);
    }

    private HttpResponse<String> send(final HttpRequest request, final String action) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            log.warn("{} 调用失败", action, e);
            throw unavailable();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} 调用被中断", action);
            throw unavailable();
        } catch (final RuntimeException e) {
            log.warn("{} 请求构造失败", action, e);
            throw unavailable();
        }
    }

    private JsonNode parse(final HttpResponse<String> response, final boolean checkOnly) {
        final String text = response.body();
        if (text == null || text.isBlank()) {
            log.warn("合约响应为空: status={}", response.statusCode());
            throw unavailable();
        }
        final JsonNode body;
        try {
            body = objectMapper.readTree(text);
        } catch (final IOException e) {
            log.warn("合约响应解析失败: status={}", response.statusCode(), e);
            throw unavailable();
        }
        if (body == null || body.isNull()) {
            log.warn("合约响应为空: status={}", response.statusCode());
            throw unavailable();
        }
        final String code = body.path("code").asText();
        if (code.startsWith("1008C")) {
            if (!PASS_THROUGH_CODES.contains(code)) {
                log.warn("合约结论码未登记: code={}", code);
                throw unavailable();
            }
            final String message = body.path("message").asText();
            throw new ContractPassThroughException(code, message, statusOf(code, response.statusCode()));
        }
        if (response.statusCode() != 200 || !"0".equals(code)) {
            log.warn("合约响应非正常: status={}, code={}", response.statusCode(), code);
            throw unavailable();
        }
        if (checkOnly) {
            return body;
        }
        return body.path("data");
    }

    private static HttpStatus statusOf(final String code, final int responseStatus) {
        if (responseStatus >= 400 && responseStatus < 500) {
            return HttpStatus.valueOf(responseStatus);
        }
        return switch (code) {
            case "1008C0012" -> HttpStatus.NOT_FOUND;
            case "1008C0013" -> HttpStatus.CONFLICT;
            case "1008C0020" -> HttpStatus.FORBIDDEN;
            case "1008C0008" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String encodePath(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static DeliveryBizException unavailable() {
        return new DeliveryBizException(DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE,
                DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE_MESSAGE);
    }
}
