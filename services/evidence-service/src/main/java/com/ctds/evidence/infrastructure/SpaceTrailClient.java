package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.AuditTrailPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 空间域 audit-trail HTTP 适配器（WBS-3.8.4 hifi §1；准入 / 策略留痕两类锚点共用一个端点）。
 * base-url 默认本机回环（本地直连演示口径），k8s 由清单注入 CTDS_EVIDENCE_SPACE_BASEURL；
 * 测试经属性注入桩地址（不依赖 env）。
 */
@Component
public class SpaceTrailClient extends InternalTrailClientSupport {

    private static final Logger log = LoggerFactory.getLogger(SpaceTrailClient.class);

    private final String baseUrl;

    public SpaceTrailClient(
            @Value("${ctds.evidence.trail.space-base-url:http://127.0.0.1:8083}")
            final String baseUrl,
            final ObjectMapper objectMapper) {
        super(objectMapper);
        this.baseUrl = baseUrl;
    }

    /** 准入锚点（admissionId）或策略留痕锚点（actionLogId）二选一。 */
    public Optional<AuditTrailPort.SpaceTrail> fetch(final long spaceId, final Long admissionId,
            final Long actionLogId) {
        final StringBuilder path = new StringBuilder(
                "/api/v1/data-spaces/internal/audit-trails?spaceId=").append(spaceId);
        if (admissionId != null) {
            path.append("&admissionId=").append(admissionId);
        }
        if (actionLogId != null) {
            path.append("&actionLogId=").append(actionLogId);
        }
        return get(log, "space", baseUrl, path.toString(), AuditTrailPort.SpaceTrail.class);
    }
}
