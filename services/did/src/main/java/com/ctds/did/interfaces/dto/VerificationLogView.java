package com.ctds.did.interfaces.dto;

import com.ctds.did.domain.VerificationLog;
import java.time.LocalDateTime;

/**
 * 验证留痕行视图（WBS-3.1.11 hifi §2.1；规格行为 3 规则 3）：
 * 时间 / DID / 结果 / 原因——**无任何数据原文与签名内容字段**（验证通道不是数据存储通道）。
 */
public record VerificationLogView(String did, String result, String reason, LocalDateTime occurredAt) {

    public static VerificationLogView from(final VerificationLog log) {
        return new VerificationLogView(log.did(), log.result().name(),
                log.reason() == null ? null : log.reason().name(), log.occurredAt());
    }
}
