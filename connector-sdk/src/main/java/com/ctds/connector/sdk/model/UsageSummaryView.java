package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 用量汇总视图（对齐平台 UsageViews.Summary：contractNo / quota / allowedCount / deniedCount；
 * 执行记录分页列表为可选消费面，骨架期不建模）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageSummaryView(String contractNo, QuotaView quota, Long allowedCount, Long deniedCount) {

    /** 配额视图（limit = 策略上限，used = 已用次数）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuotaView(Integer limit, Integer used) {
    }
}
