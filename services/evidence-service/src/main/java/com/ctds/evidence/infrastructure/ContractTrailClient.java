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
 * 合约域 audit-trail HTTP 适配器（WBS-3.8.4 hifi §1；合约锚点 + 使用放行锚点两条路径）。
 * base-url 默认本机回环，k8s 由清单注入 CTDS_EVIDENCE_CONTRACT_BASEURL；测试经属性注入桩地址。
 */
@Component
public class ContractTrailClient extends InternalTrailClientSupport {

    private static final Logger log = LoggerFactory.getLogger(ContractTrailClient.class);

    private final String baseUrl;

    public ContractTrailClient(
            @Value("${ctds.evidence.trail.contract-base-url:http://127.0.0.1:8085}")
            final String baseUrl,
            final ObjectMapper objectMapper) {
        super(objectMapper);
        this.baseUrl = baseUrl;
    }

    /** 合约锚点：存证要素 + 统一留痕（路径段沿 UriUtils.encodePathSegment 先例编码）。 */
    public Optional<AuditTrailPort.ContractTrail> fetchContract(final String contractNo) {
        return get(log, "contract", baseUrl,
                "/api/v1/contracts/internal/" + UriUtils.encodePathSegment(contractNo,
                        StandardCharsets.UTF_8) + "/audit-trail",
                AuditTrailPort.ContractTrail.class);
    }

    /** 使用放行锚点：单行留痕。 */
    public Optional<AuditTrailPort.UsageTrail> fetchUsage(final long usageLogId) {
        return get(log, "contract", baseUrl,
                "/api/v1/contracts/internal/usage-logs/" + usageLogId + "/audit-trail",
                AuditTrailPort.UsageTrail.class);
    }
}
