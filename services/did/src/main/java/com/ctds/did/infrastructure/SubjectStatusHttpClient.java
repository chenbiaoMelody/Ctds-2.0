package com.ctds.did.infrastructure;

import com.ctds.did.domain.SubjectAdmission;
import com.ctds.did.domain.SubjectStatusPort;
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
 * DID → 主体服务 状态只读客户端（JDK HttpClient，沿 KmsKeyProvider/DidIssuanceClient 先例，零新增依赖）。
 * 单一事实源在主体服务（ADR-017 §2.2）：本客户端只读不缓存；服务身份头 did-service / did-internal（只读角色）。
 * 未配置 base-url = 绑定核验不可用（服务可独立启动）；任何失败（不可达/超时/非 200/解析失败）→ UNAVAILABLE，
 * 不冒充"验证不通过"（WBS-3.1.9 hifi §5/B9）。
 */
@Component
public class SubjectStatusHttpClient implements SubjectStatusPort {

    private static final Logger log = LoggerFactory.getLogger(SubjectStatusHttpClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String INTERNAL_SUBJECT = "did-service";
    private static final String INTERNAL_ROLES = "did-internal";
    private static final String STATUS_ADMITTED = "ADMITTED";
    /** 主体不存在的业务码（1000 段 RESOURCE_NOT_FOUND）：绑定不成立（非"不可用"）。 */
    private static final String SUBJECT_NOT_FOUND_CODE = "1000C0003";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SubjectStatusHttpClient(@Value("${ctds.did.subject.base-url:}") final String baseUrl,
            final ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public SubjectAdmission check(final String subjectNo) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("ctds.did.subject.base-url 未配置，绑定核验不可用: subjectNo={}", subjectNo);
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
            log.warn("主体服务不可达，绑定核验不可用: subjectNo={}", subjectNo, e);
            return SubjectAdmission.UNAVAILABLE;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("主体服务调用被中断，绑定核验不可用: subjectNo={}", subjectNo);
            return SubjectAdmission.UNAVAILABLE;
        }
        final JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (final JsonProcessingException e) {
            log.warn("主体服务响应解析失败，绑定核验不可用: subjectNo={}, status={}", subjectNo,
                    response.statusCode(), e);
            return SubjectAdmission.UNAVAILABLE;
        }
        final String code = body.path("code").asText();
        // 主体编号不存在（业务答复 1000C0003，主体服务以 400 返回）= 绑定不成立（hifi §5）
        if (SUBJECT_NOT_FOUND_CODE.equals(code)) {
            return SubjectAdmission.NOT_ADMITTED;
        }
        // 非 200 或业务码非 0：一律如实归"不可用"（hifi §6：不得把系统态当作绑定成立/不成立）
        if (response.statusCode() != 200 || !"0".equals(code)) {
            log.warn("主体服务响应非正常，绑定核验不可用: subjectNo={}, status={}, code={}", subjectNo,
                    response.statusCode(), code);
            return SubjectAdmission.UNAVAILABLE;
        }
        final String status = body.path("data").path("status").asText();
        return STATUS_ADMITTED.equals(status) ? SubjectAdmission.ADMITTED : SubjectAdmission.NOT_ADMITTED;
    }
}