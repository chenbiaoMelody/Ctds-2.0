package com.ctds.connector.domain;

import java.time.LocalDateTime;

/**
 * 源连通性自检结论（规格行为 13）：由动作留痕 {@code source.check} 派生（最近一条），不建第二事实源。
 *
 * @param sourceNo    源编号
 * @param passed      是否通过
 * @param failureType 失败原因（通过时为 null）
 * @param message     业务可读说明
 * @param checkedAt   自检时间
 */
public record ConnectorSourceCheck(String sourceNo, boolean passed, String failureType, String message,
        LocalDateTime checkedAt) {
}
