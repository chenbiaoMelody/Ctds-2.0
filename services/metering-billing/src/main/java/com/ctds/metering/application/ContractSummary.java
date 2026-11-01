package com.ctds.metering.application;

import java.util.List;

/** 本方合约按自然月聚合（R-M2 响应；B8-1 查询承载）。 */
public record ContractSummary(String contractNo, List<PeriodSummary> periods) {
}
