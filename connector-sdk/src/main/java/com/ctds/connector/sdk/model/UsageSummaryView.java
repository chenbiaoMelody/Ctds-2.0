package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 用量汇总视图（只读——合约域用量口径）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageSummaryView(String contractNo, Long usedCount) {
}
