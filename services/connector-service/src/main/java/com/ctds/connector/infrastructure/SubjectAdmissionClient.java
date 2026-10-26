package com.ctds.connector.infrastructure;

import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.connector.config.ConnectorProperties;
import com.ctds.connector.domain.SubjectAdmission;
import com.ctds.connector.domain.SubjectAdmissionPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * 主体 → 入驻资格只读客户端（hifi §8；JDK HttpClient 零新增依赖，沿 catalog / delivery 先例）。
 * 单一事实源在主体域：只读不缓存；服务身份头 connector-service / connector-internal。
 * 未配置 / 不可达 / 超时 / 非 200 / 不可解析 → UNAVAILABLE（**不冒充未入驻**，调用方 fail-closed 503）。
 */
@Component
public class SubjectAdmissionClient implements SubjectAdmissionPort {

    private static final Logger log = LoggerFactory.getLogger(SubjectAdmissionClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final String INTERNAL_SUBJECT = "connector-service";
    private static final String INTERNAL_ROLES = "connector-internal";
    private static final String STATUS_ADMITTED = "ADMITTED";
    /** 主体不存在的平台业务码（主体不存在 = 资格不成立，与未入驻同文防枚举；复用 common 常量）。 */
    private static final String SUBJECT_NOT_FOUND_CODE = ErrorCodes.RESOURCE_NOT_FOUND.value();

    private final String baseUrl;
    private final Duration readTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SubjectAdmissionClient(final ConnectorProperties properties, final ObjectMapper objectMapper) {
        this.baseUrl = properties.getSubject().getBaseUrl();
        this.readTimeout = properties.getSubject().getTimeout();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public SubjectAdmission check(final String subjectNo) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("ctds.connector.subject.base-url 未配置，资格判定不可用: subjectNo={}", subjectNo);
            return SubjectAdmission.UNAVAILABLE;
        }
        final HttpResponse<String> response;
        try {
            final String path = UriUtils.encodePathSegment(subjectNo, StandardCharsets.UTF_8);
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/subject/internal/subjects/" + path + "/admission"))
                    .timeout(readTimeout)
                    .header("X-Ctds-Subject", INTERNAL_SUBJECT)
                    .header("X-Ctds-Roles", INTERNAL_ROLES)
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException e) {
            log.warn("主体服务不可达，资格判定不可用: subjectNo={}", subjectNo, e);
            return SubjectAdmission.UNAVAILABLE;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("主体服务调用被中断，资格判定不可用: subjectNo={}", subjectNo);
            return SubjectAdmission.UNAVAILABLE;
        } catch (final RuntimeException e) {
            log.warn("主体服务请求构造失败，资格判定不可用: subjectNo={}", subjectNo, e);
            return SubjectAdmission.UNAVAILABLE;
        }
        final JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (final JsonProcessingException e) {
            log.warn("主体服务响应解析失败: subjectNo={}, status={}", subjectNo, response.statusCode(), e);
            return SubjectAdmission.UNAVAILABLE;
        }
        final String code = body.path("code").asText();
        if (SUBJECT_NOT_FOUND_CODE.equals(code)) {
            return SubjectAdmission.NOT_ADMITTED;
        }
        if (response.statusCode() != 200 || !"0".equals(code)) {
            log.warn("主体服务响应非正常: subjectNo={}, status={}, code={}", subjectNo,
                    response.statusCode(), code);
            return SubjectAdmission.UNAVAILABLE;
        }
        final String status = body.path("data").path("status").asText();
        return STATUS_ADMITTED.equals(status) ? SubjectAdmission.ADMITTED : SubjectAdmission.NOT_ADMITTED;
    }
}
