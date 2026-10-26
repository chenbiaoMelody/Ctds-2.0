package com.ctds.connector.application;

import com.ctds.connector.domain.ConnectorBinding;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.VerificationCheck;

/**
 * 核验结论（HTTP 200 业务结论，非接口错误）：
 * verified = true 时 registration 已转 REGISTERED 且 binding 已建立；false 时 failedCheck 标明首个不通过项。
 */
public record VerificationOutcome(
        boolean verified,
        VerificationCheck failedCheck,
        String reasonCode,
        String reason,
        ConnectorRegistration registration,
        ConnectorBinding binding) {
}
