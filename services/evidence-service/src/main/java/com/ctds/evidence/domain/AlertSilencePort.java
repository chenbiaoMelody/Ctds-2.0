package com.ctds.evidence.domain;

import java.time.Instant;

/**
 * 告警静默端口（WBS-3.9.4 处置静默链路唯一依赖；Q4-A：告警列表读面不依赖本端口）。
 * 实现方 = Alertmanager /api/v2/silences（v0.34.0 官方 postableSilence 形状，P7 核对）；
 * 不可达 / 非 2xx → 抛业务异常 `1013S0003`（出站 503，处置不留"OK"痕）。
 */
public interface AlertSilencePort {

    /** 创建按告警名精确匹配的静默；返回 silenceId（入处置留痕 detail）。 */
    String createSilence(String alertName, Instant startsAt, Instant endsAt,
            String createdBy, String comment);
}
