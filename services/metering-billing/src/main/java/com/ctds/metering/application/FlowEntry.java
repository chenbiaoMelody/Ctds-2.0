package com.ctds.metering.application;

import com.ctds.metering.domain.MeteringSourceType;
import java.time.LocalDateTime;

/** 本方合约流水条目（R-M1 响应 items；B7-1 逐条可溯）。 */
public record FlowEntry(MeteringSourceType sourceType, String sourceNo, String action,
        String resource, LocalDateTime occurredAt) {
}
