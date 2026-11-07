package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.AuditTrailPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.web.util.UriUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 交付域 audit-trail HTTP 适配器（WBS-3.8.4 hifi §1；交付锚点）。
 * base-url 默认本机回环，k8s 由清单注入 CTDS_EVIDENCE_DELIVERY_BASEURL；测试经属性注入桩地址。
 */
@Component
public class DeliveryTrailClient extends InternalTrailClientSupport {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTrailClient.class);

    private final String baseUrl;

    public DeliveryTrailClient(
            @Value("${ctds.evidence.trail.delivery-base-url:http://127.0.0.1:8087}")
            final String baseUrl,
            final ObjectMapper objectMapper) {
        super(objectMapper);
        this.baseUrl = baseUrl;
    }

    /** 交付锚点：交付记录 + 留痕（路径段沿 UriUtils.encodePathSegment 先例编码）。 */
    public Optional<AuditTrailPort.DeliveryTrail> fetch(final String deliveryNo) {
        return get(log, "delivery", baseUrl,
                "/api/v1/deliveries/internal/" + UriUtils.encodePathSegment(deliveryNo,
                        StandardCharsets.UTF_8) + "/audit-trail",
                AuditTrailPort.DeliveryTrail.class);
    }
}
