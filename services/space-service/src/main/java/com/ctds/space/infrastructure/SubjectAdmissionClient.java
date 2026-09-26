package com.ctds.space.infrastructure;

import com.ctds.space.domain.SubjectAdmission;
import com.ctds.space.domain.SubjectAdmissionPort;
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
 * 空间 → 主体服务 资格只读客户端（WBS-3.2.3 hifi §4 Q1-A；JDK HttpClient 零新增依赖，
 * 沿 did SubjectStatusHttpClient 93 行先例）。单一事实源在主体服务（ADR-016 §6 衔接契约）：
 * 本客户端只读不缓存；服务身份头 space-service / space-internal（只读角色，subject yml 1 行授权）。
 * 未配置 base-url = 资格判定不可用（服务可独立启动）；任何失败（不可达/超时/非 200/解析失败）→
 * UNAVAILABLE（1006S0001），不冒充"未入驻"（防枚举口径，hifi §4）。
 */
@Component
public class SubjectAdmissionClient implements SubjectAdmissionPort {

    private static final Logger log = LoggerFactory.getLogger(SubjectAdmissionClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String INTERNAL_SUBJECT = "space-service";
    private static final String INTERNAL_ROLES = "space-internal";
    private static final String STATUS_ADMITTED = "ADMITTED";
    /** 主体不存在的业务码（1000 段 RESOURCE_NOT_FOUND）：主体不存在 = 资格不成立（统一文案防枚举）。 */
    private static final String SUBJECT_NOT_FOUND_CODE = "1000C0003";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SubjectAdmissionClient(@Value("${ctds.space.subject.base-url:}") final String baseUrl,
            final ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public SubjectAdmission check(final String subjectNo) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("ctds.space.subject.base-url 未配置，资格判定不可用: subjectNo={}", subjectNo);
            return SubjectAdmission.UNAVAILABLE;
        }
        final HttpResponse<String> response;
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/subject/internal/subjects/" + subjectNo + "/admission"))
                    .timeout(READ_TIMEOUT)
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
        }
        final JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (final JsonProcessingException e) {
            log.warn("主体服务响应解析失败，资格判定不可用: subjectNo={}, status={}", subjectNo,
                    response.statusCode(), e);
            return SubjectAdmission.UNAVAILABLE;
        }
        final String code = body.path("code").asText();
        // 主体编号不存在（业务答复 1000C0003）= 资格不成立（与未入驻同文案 1006C0001，防枚举）
        if (SUBJECT_NOT_FOUND_CODE.equals(code)) {
            return SubjectAdmission.NOT_ADMITTED;
        }
        // 非 200 或业务码非 0：一律如实归"不可用"（不把系统态当作资格成立/不成立，hifi §4）
        if (response.statusCode() != 200 || !"0".equals(code)) {
            log.warn("主体服务响应非正常，资格判定不可用: subjectNo={}, status={}, code={}", subjectNo,
                    response.statusCode(), code);
            return SubjectAdmission.UNAVAILABLE;
        }
        final String status = body.path("data").path("status").asText();
        return STATUS_ADMITTED.equals(status) ? SubjectAdmission.ADMITTED : SubjectAdmission.NOT_ADMITTED;
    }
}
