package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
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
 * 目录 → 空间服务 成员关系只读客户端（WBS-3.3.2 hifi §5；JDK HttpClient 零新增依赖，
 * 沿 space SubjectAdmissionClient 形态）。目标 = space 内部端点
 * {@code GET /api/v1/data-spaces/internal/{spaceId}/memberships/{subjectNo}}（本卡 Q2-A 新增，
 * 最小暴露 spaceStatus + role|NONE）。空间状态与成员口径唯一事实源在空间服务——本客户端只读不缓存；
 * 服务身份头 catalog-service / catalog-internal（space yml 1 行授权）。
 * 未配置 base-url = 空间判定不可用（服务可独立启动）；任何失败（不可达/超时/非 200/解析失败/
 * 请求构造失败）→ UNAVAILABLE（1007S0002），不冒充"非成员/空间不存在"（防枚举，hifi §5）。
 */
@Component
public class SpaceMembershipClient implements SpaceMembershipPort {

    private static final Logger log = LoggerFactory.getLogger(SpaceMembershipClient.class);
    /** 连接超时（hifi §5 契约：connectTimeout 2s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** 读超时（hifi §5 契约：readTimeout 3s）。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String INTERNAL_SUBJECT = "catalog-service";
    private static final String INTERNAL_ROLES = "catalog-internal";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SpaceMembershipClient(@Value("${ctds.catalog.space.base-url:}") final String baseUrl,
            final ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public SpaceMembership check(final long spaceId, final String subjectNo) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("ctds.catalog.space.base-url 未配置，空间成员判定不可用: spaceId={}", spaceId);
            return SpaceMembership.unavailable();
        }
        final HttpResponse<String> response;
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/data-spaces/internal/" + spaceId
                            + "/memberships/" + subjectNo))
                    .timeout(READ_TIMEOUT)
                    .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                    .header("X-Ctds-Roles", INTERNAL_ROLES)
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException e) {
            log.warn("空间服务不可达，空间成员判定不可用: spaceId={}", spaceId, e);
            return SpaceMembership.unavailable();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("空间服务调用被中断，空间成员判定不可用: spaceId={}", spaceId);
            return SpaceMembership.unavailable();
        } catch (final RuntimeException e) {
            // 请求构造失败（畸形空间 id/主体编号致 URI 非法等）同归 UNAVAILABLE：fail-closed
            log.warn("空间服务请求构造失败，空间成员判定不可用: spaceId={}", spaceId, e);
            return SpaceMembership.unavailable();
        }
        final JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (final JsonProcessingException e) {
            log.warn("空间服务响应解析失败，空间成员判定不可用: spaceId={}, status={}", spaceId,
                    response.statusCode(), e);
            return SpaceMembership.unavailable();
        }
        // 非 200 或业务码非 0：一律如实归"不可用"（不把系统态当作非成员/空间不存在）
        if (response.statusCode() != 200 || !"0".equals(body.path("code").asText())) {
            log.warn("空间服务响应非正常，空间成员判定不可用: spaceId={}, status={}, code={}", spaceId,
                    response.statusCode(), body.path("code").asText());
            return SpaceMembership.unavailable();
        }
        final JsonNode data = body.path("data");
        return SpaceMembership.of(data.path("spaceStatus").asText(SpaceMembership.STATUS_NONE),
                data.path("role").asText(SpaceMembership.ROLE_NONE));
    }
}
