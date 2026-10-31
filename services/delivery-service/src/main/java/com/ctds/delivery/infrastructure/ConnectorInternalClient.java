package com.ctds.delivery.infrastructure;

import com.ctds.delivery.config.DeliveryProperties;
import com.ctds.delivery.domain.ConnectorInternalException;
import com.ctds.delivery.domain.ConnectorInternalPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.web.util.UriUtils;

/**
 * 连接器内部面客户端（WBS-3.6.8 W3 / W4，hifi §1-D / §1-E）：交付链消费连接器既有四个内部端点
 * （占用登记 / 释放 / 读连接器属主 / 内部源读取）。JDK HttpClient 零新增依赖（沿
 * {@code connector-service.SubjectAdmissionClient} 同构）；固定服务身份头
 * {@code X-Ctds-Subject: delivery-service} + {@code X-Ctds-Roles: connector-internal}
 * （演示期信任边界 DB-03 沿承；任何档位不混入业务身份）。
 *
 * <p>错误收敛：未配置 → fail-closed（CONNECTOR 交付不可用）；不可达 / 超时 / 非 2xx / 业务拒绝
 * → {@link ConnectorInternalException}（业务可读原因，<b>不含内部码、内部实现与源信息</b>）；
 * 不重试（ADR-026 A4 维持不做）。占用 / 释放幂等；释放失败仅告警不阻断（finally 兜底语义，
 * 进程崩溃残留由占用清单端点运维处置，hifi §1-B 事务边界如实登记）。</p>
 */
@Component
public class ConnectorInternalClient implements ConnectorInternalPort {

    private static final Logger log = LoggerFactory.getLogger(ConnectorInternalClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final String INTERNAL_SUBJECT = "delivery-service";
    private static final String INTERNAL_ROLES = "connector-internal";

    private final String baseUrl;
    private final Duration readTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ConnectorInternalClient(final DeliveryProperties properties,
            final ObjectMapper objectMapper) {
        this.baseUrl = properties.getConnector().getBaseUrl();
        this.readTimeout = properties.getConnector().getTimeout();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public void occupy(final String connectorNo, final String jobRef) {
        requireConfigured();
        final ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jobRef", jobRef);
        exchange("POST", path(connectorNo, "/occupancies"), writeBody(payload), "连接器占用登记被拒");
    }

    @Override
    public void release(final String connectorNo, final String jobRef) {
        requireConfigured();
        try {
            exchange("DELETE", path(connectorNo, "/occupancies/"
                    + UriUtils.encodePathSegment(jobRef, StandardCharsets.UTF_8)), null, "连接器释放被拒");
        } catch (final ConnectorInternalException e) {
            // 释放失败不阻断（finally 兜底语义）；残留占用经内部清单端点可查、可幂等补释放
            log.warn("连接器占用释放失败（不阻断交付主流程）: connectorNo={}, jobRef={}",
                    connectorNo, jobRef);
        }
    }

    @Override
    public String getConnectorOwnerSubjectNo(final String connectorNo) {
        requireConfigured();
        final JsonNode data = exchange("GET", readConnectorPath(connectorNo), null, "连接器不可达");
        final String subjectNo = data.path("subjectNo").asText(null);
        if (subjectNo == null || subjectNo.isBlank()) {
            throw new ConnectorInternalException("连接器属主信息不可用");
        }
        return subjectNo;
    }

    @Override
    public ConnectorSourceRead readSource(final String connectorNo, final String sourceNo,
            final String jobRef, final String contractNo, final String purpose) {
        requireConfigured();
        final ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jobRef", jobRef);
        payload.put("contractNo", contractNo);
        payload.put("purpose", purpose);
        final JsonNode data = exchange("POST", readSourcePath(connectorNo, sourceNo),
                writeBody(payload), "连接器服务暂不可用或拒绝读取");
        final boolean success = data.path("success").asBoolean(false);
        final String summary = data.path("summary").asText("");
        return new ConnectorSourceRead(success, summary);
    }

    private String writeBody(final ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (final JsonProcessingException e) {
            throw new ConnectorInternalException("内部面请求构造失败", e);
        }
    }

    private String path(final String connectorNo, final String suffix) {
        return "/api/v1/connectors/internal/"
                + UriUtils.encodePathSegment(connectorNo, StandardCharsets.UTF_8) + suffix;
    }

    private String readConnectorPath(final String connectorNo) {
        return path(connectorNo, "");
    }

    private String readSourcePath(final String connectorNo, final String sourceNo) {
        return path(connectorNo, "/sources/"
                + UriUtils.encodePathSegment(sourceNo, StandardCharsets.UTF_8) + "/reads");
    }

    private JsonNode exchange(final String method, final String path, final String body,
            final String reason) {
        final HttpResponse<String> response;
        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(readTimeout)
                    .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                    .header("X-Ctds-Roles", INTERNAL_ROLES);
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else if ("DELETE".equals(method)) {
                builder.DELETE();
            } else {
                builder.GET();
            }
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (final IOException e) {
            throw new ConnectorInternalException(reason, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorInternalException(reason, e);
        } catch (final RuntimeException e) {
            throw new ConnectorInternalException(reason, e);
        }
        if (response.statusCode() != 200) {
            throw new ConnectorInternalException(reason);
        }
        final JsonNode parsed;
        try {
            parsed = objectMapper.readTree(response.body());
        } catch (final IOException e) {
            throw new ConnectorInternalException(reason, e);
        }
        if (parsed == null || !"0".equals(parsed.path("code").asText(null))) {
            throw new ConnectorInternalException(reason);
        }
        return parsed.path("data");
    }

    private void requireConfigured() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ConnectorInternalException("连接器服务未配置，CONNECTOR 交付不可用");
        }
    }
}
